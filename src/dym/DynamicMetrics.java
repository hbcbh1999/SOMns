package dym;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import som.compiler.Tags;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.EventNode;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.Builder;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Registration;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.utilities.JSONHelper;
import com.oracle.truffle.api.utilities.JSONHelper.JSONArrayBuilder;
import com.oracle.truffle.api.utilities.JSONHelper.JSONObjectBuilder;

import dym.nodes.ControlFlowProfileNode;
import dym.nodes.CountingNode;
import dym.nodes.InvocationProfilingNode;
import dym.profiles.BranchProfile;
import dym.profiles.Counter;
import dym.profiles.InvocationProfile;
import dym.profiles.MethodCallsiteProbe;


/**
 * DynamicMetric is a Truffle instrumentation tool to measure a wide range of
 * dynamic metrics to characterize the behavior of executing code.
 *
 * WARNING:
 *   - designed for single-threaded use only
 *   - designed for use in interpreted mode only
 */
@Registration(id = DynamicMetrics.ID, autostart = false)
public class DynamicMetrics extends TruffleInstrument {

  public static final String ID       = "dym-dynamic-metrics";

  // Tags used by the DynamicMetrics tool
  public static final String ROOT_TAG           = "ROOT";
  public static final String UNSPECIFIED_INVOKE = "UNSPECIFIED_INVOKE"; // this is some form of invoke in the source, unclear what it is during program execution
  public static final String INVOKE_WITH_LOOKUP = "INVOKE_WITH_LOOKUP";
  public static final String NEW_OBJECT         = "NEW_OBJECT";
  public static final String NEW_ARRAY          = "NEW_ARRAY";
  public static final String CONTROL_FLOW_CONDITION  = "CONTROL_FLOW_CONDITION"; // a condition expression that results in a control-flow change

  // TODO
  public static final String FIELD_READ         = "FIELD_READ";
  public static final String FIELD_WRITE        = "FIELD_WRITE";
  public static final String ARRAY_READ         = "ARRAY_READ";
  public static final String ARRAY_WRITE        = "ARRAY_WRITE";
  public static final String LOOP_BODY          = "LOOP_BODY";


  private final Map<SourceSection, InvocationProfile> methodInvocationCounter;
  private int methodStackDepth;
  private int maxStackDepth;

  private final Map<SourceSection, MethodCallsiteProbe> methodCallsiteProbes;
  private final Map<SourceSection, Counter> instantiationCounter;
  private final Map<SourceSection, Counter> fieldAccessCounter;
  private final Map<SourceSection, BranchProfile> controlFlowProfiles;

  public DynamicMetrics() {
    methodInvocationCounter = new HashMap<>();
    methodCallsiteProbes    = new HashMap<>();
    instantiationCounter    = new HashMap<>();
    fieldAccessCounter      = new HashMap<>();
    controlFlowProfiles     = new HashMap<>();

    assert "DefaultTruffleRuntime".equals(
        Truffle.getRuntime().getClass().getSimpleName())
        : "To get metrics for the lexical, unoptimized behavior, please run this tool without Graal";
  }

  public void enterMethod() {
    methodStackDepth += 1;
    maxStackDepth = Math.max(methodStackDepth, maxStackDepth);
    assert methodStackDepth > 0;
  }

  public void leaveMethod() {
    methodStackDepth -= 1;
    assert methodStackDepth >= 0;
  }

  @Override
  protected void onCreate(final Env env, final Instrumenter instrumenter) {
    Builder filters = SourceSectionFilter.newBuilder();
    filters.tagIs(ROOT_TAG);
    instrumenter.attachFactory(
        filters.build(),
        (final EventContext context) -> {
          return createInvocationCountingNode(context);
        });

    filters = SourceSectionFilter.newBuilder();
    filters.tagIs(UNSPECIFIED_INVOKE);
    instrumenter.attachFactory(
        filters.build(),
        (final EventContext context) -> {
          return createMethodCallsiteNode(context);
        });

    filters = SourceSectionFilter.newBuilder();
    filters.tagIs(NEW_OBJECT, NEW_ARRAY);
    instrumenter.attachFactory(
        filters.build(),
        (final EventContext context) -> {
          Counter counter = instantiationCounter.computeIfAbsent(
              context.getInstrumentedSourceSection(), src -> new Counter(src));
          return new CountingNode(counter);
        });

    filters = SourceSectionFilter.newBuilder();
    filters.tagIs(FIELD_READ); // TODO: FIELD_WRITE
    instrumenter.attachFactory(
        filters.build(),
        (final EventContext context) -> {
          Counter counter = fieldAccessCounter.computeIfAbsent(
              context.getInstrumentedSourceSection(), src -> new Counter(src));
          return new CountingNode(counter);
        });

    filters = SourceSectionFilter.newBuilder();
    filters.tagIs(Tags.CONTROL_FLOW_CONDITION);
    instrumenter.attachFactory(
        filters.build(),
        (final EventContext context) -> {
          BranchProfile profile = controlFlowProfiles.computeIfAbsent(
              context.getInstrumentedSourceSection(), src -> new BranchProfile(src));
          return new ControlFlowProfileNode(profile);
        });
  }

  @Override
  protected void onDispose(final Env env) {
    @SuppressWarnings("unused")
    int i = 0;
    json();
  }

  private EventNode createInvocationCountingNode(final EventContext context) {
    SourceSection source = context.getInstrumentedSourceSection();
    InvocationProfile counter = methodInvocationCounter.computeIfAbsent(
        source, src -> new InvocationProfile(src));
    return new InvocationProfilingNode(this, counter);
  }

  private EventNode createMethodCallsiteNode(final EventContext context) {
    SourceSection source = context.getInstrumentedSourceSection();
    MethodCallsiteProbe probe = methodCallsiteProbes.computeIfAbsent(
        source, src -> new MethodCallsiteProbe(src));
    return new CountingNode(probe);
  }

  private void json() {
    Set<SourceSection> allSections = new HashSet<>(methodInvocationCounter.keySet());
    allSections.addAll(methodInvocationCounter.keySet());
    allSections.addAll(methodCallsiteProbes.keySet());
    allSections.addAll(instantiationCounter.keySet());
    allSections.addAll(fieldAccessCounter.keySet());
    allSections.addAll(controlFlowProfiles.keySet());

    Set<Source> allSources = new HashSet<>();
    allSections.forEach(ss -> allSources.add(ss.getSource()));

    Map<Source, String> sourceToId = createIdMap(allSources, "s-");
    Map<SourceSection, String> sectionToId = createIdMap(allSections, "ss-");

    JSONObjectBuilder allSourcesJson = JSONHelper.object();
    for (Source s : allSources) {
      String id = sourceToId.get(s);
      assert id != null && !id.equals("");
      allSourcesJson.add(id, sourceToJson(s, id));
    }

    JSONObjectBuilder allSectionsJson = JSONHelper.object();
    for (SourceSection ss : allSections) {
      allSectionsJson.add(sectionToId.get(ss), sectionToJson(ss, sectionToId.get(ss), sourceToId));
    }

    JSONObjectBuilder root = JSONHelper.object();
    root.add("sources", allSourcesJson);
    root.add("sections", allSectionsJson);

    try {
      String cwd = new File(".").getCanonicalPath();
      String jsonFilename = cwd + File.separator + "dynamic-metrics.json";

      System.out.println("[DynamicMetrics] Create output file: " + jsonFilename);

      try (PrintWriter jsonFile = new PrintWriter(jsonFilename)) {
        jsonFile.println(root.toString());
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private JSONObjectBuilder collectDataForSection(final SourceSection section) {
    JSONObjectBuilder result = JSONHelper.object();
    InvocationProfile profile = methodInvocationCounter.get(section);
    if (profile != null) {
      result.add("methodInvocationProfile", profile.toJson());
    }
    return result;
  }

  private JSONObjectBuilder sectionToJson(final SourceSection ss, final String id, final Map<Source, String> sourceToId) {
    JSONObjectBuilder builder = JSONHelper.object();

    builder.add("id", id);
    builder.add("firstIndex", ss.getCharIndex());
    builder.add("length", ss.getCharLength());
    builder.add("identifier", ss.getIdentifier());
    builder.add("description", ss.getShortDescription());
    builder.add("sourceId", sourceToId.get(ss.getSource()));


    if (ss.getTags() != null && ss.getTags().length > 0) {
      JSONArrayBuilder arr = JSONHelper.array();
      for (String tag : ss.getTags()) {
        arr.add(tag);
      }
      builder.add("tags", arr);
    }
    builder.add("data", collectDataForSection(ss));

    return builder;
  }

  private JSONObjectBuilder sourceToJson(final Source s, final String id) {
    JSONObjectBuilder builder = JSONHelper.object();
    builder.add("id", id);
    builder.add("sourceText", s.getCode());
    builder.add("mimeType", s.getMimeType());
    builder.add("name", s.getName());
    builder.add("shortName", s.getShortName());
    return builder;
  }

  private <U> Map<U, String> createIdMap(final Set<U> set, final String idPrefix) {
    Map<U, String> eToId = new HashMap<>();

    int i = 0;
    for (U e : set) {
      eToId.put(e, idPrefix + i);
      i += 1;
    }
    return eToId;
  }
}
