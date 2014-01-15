/**
 * Copyright (c) 2013 Stefan Marr,   stefan.marr@vub.ac.be
 * Copyright (c) 2009 Michael Haupt, michael.haupt@hpi.uni-potsdam.de
 * Software Architecture Group, Hasso Plattner Institute, Potsdam, Germany
 * http://www.hpi.uni-potsdam.de/swa/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package som.primitives;

import som.compiler.MethodGenerationContext;
import som.interpreter.Primitive;
import som.interpreter.nodes.AbstractMessageNode;
import som.interpreter.nodes.ArgumentEvaluationNode;
import som.interpreter.nodes.ArgumentReadNode;
import som.interpreter.nodes.ExpressionNode;
import som.vm.Universe;
import som.vmobjects.SClass;
import som.vmobjects.SMethod;
import som.vmobjects.SSymbol;

import com.oracle.truffle.api.CompilerDirectives.SlowPath;
import com.oracle.truffle.api.dsl.NodeFactory;

public abstract class Primitives {

  protected final Universe universe;

  public Primitives(final Universe universe) {
    this.universe = universe;
  }

  public final void installPrimitivesIn(final SClass value) {
    // Save a reference to the holder class
    holder = value;

    // Install the primitives from this primitives class
    installPrimitives();
  }

  public abstract void installPrimitives();

  @SlowPath
  public static SMethod constructPrimitive(final SSymbol signature,
      final NodeFactory<? extends AbstractMessageNode> nodeFactory,
      final Universe universe, final SClass holder) {
    int numArgs = signature.getNumberOfSignatureArguments();

    MethodGenerationContext mgen = new MethodGenerationContext();
    ArgumentReadNode self = new ArgumentReadNode(0);
    ExpressionNode[] args = new ExpressionNode[numArgs - 1];
    for (int i = 0; i < numArgs - 1; i++) {
      args[i] = new ArgumentReadNode(i + 1);
    }

    AbstractMessageNode primNode;
    if (numArgs == 1) {
      primNode = nodeFactory.createNode(signature, universe, self);
    } else if (numArgs == 2) {
      primNode = nodeFactory.createNode(signature, universe, self, args[0]);
    } else if (numArgs == 3) {
      primNode = nodeFactory.createNode(signature, universe, self, args[0], args[1]);
    } else {
      ArgumentEvaluationNode argEvalNode = new ArgumentEvaluationNode(args);
      primNode = nodeFactory.createNode(signature, universe,
          self, argEvalNode);
    }

    Primitive primMethodNode = new Primitive(primNode, mgen.getFrameDescriptor());
    SMethod prim = universe.newMethod(signature, primMethodNode, true);
    return prim;
  }

  @SlowPath
  public static SMethod constructEmptyPrimitive(final SSymbol signature,
      final Universe universe) {
    int numArgs = signature.getNumberOfSignatureArguments();

    MethodGenerationContext mgen = new MethodGenerationContext();
    ArgumentReadNode self = new ArgumentReadNode(0);
    ExpressionNode[] args = new ExpressionNode[numArgs - 1];
    for (int i = 0; i < numArgs - 1; i++) {
      args[i] = new ArgumentReadNode(i + 1);
    }

    ExpressionNode primNode = EmptyPrim.create(signature, universe, self);
    Primitive primMethodNode = new Primitive(primNode, mgen.getFrameDescriptor());
    SMethod prim = universe.newMethod(signature, primMethodNode, true);
    return prim;
  }

  protected void installInstancePrimitive(final String selector,
      final NodeFactory<? extends AbstractMessageNode> nodeFactory) {
    SSymbol signature = universe.symbolFor(selector);
    SMethod prim = constructPrimitive(signature, nodeFactory, universe, holder);

    // Install the given primitive as an instance primitive in the holder class
    holder.addInstancePrimitive(prim);
  }

  protected void installClassPrimitive(final String selector,
      final NodeFactory<? extends AbstractMessageNode> nodeFactory) {
    SSymbol signature = universe.symbolFor(selector);
    SMethod prim = constructPrimitive(signature, nodeFactory, universe, holder);

    // Install the given primitive as an instance primitive in the class of
    // the holder class
    holder.getSOMClass(universe).addInstancePrimitive(prim);
  }

  protected SClass holder;

  public static SMethod getEmptyPrimitive(final String selector,
      final Universe universe) {
    SSymbol signature = universe.symbolFor(selector);
    return constructEmptyPrimitive(signature, universe);
  }
}
