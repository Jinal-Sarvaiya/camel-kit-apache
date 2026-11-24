package org.apache.camel.kit.graph.adapter.mule;

import java.util.Map;
import java.util.Optional;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.RuntimeDetector;
import org.apache.camel.kit.graph.model.*;

public final class MuleGraphAdapter implements GraphTypeAdapter {
    public static final NodeType FLOW = new NodeType("mule:flow");
    public static final NodeType SUB_FLOW = new NodeType("mule:sub-flow");
    public static final NodeType CONNECTOR = new NodeType("mule:connector");
    public static final NodeType ENDPOINT = new NodeType("mule:endpoint");
    public static final NodeType PROCESSOR = new NodeType("mule:processor");
    public static final NodeType TRANSFORM = new NodeType("mule:transform");
    public static final NodeType ERROR_HANDLER = new NodeType("mule:error-handler");
    public static final NodeType DATAWEAVE_SCRIPT = new NodeType("mule:dataweave-script");
    public static final EdgeType FLOW_CONTAINS = new EdgeType("mule:flow-contains");
    public static final EdgeType CALLS_SUBFLOW = new EdgeType("mule:calls-subflow");
    public static final EdgeType USES_CONNECTOR = new EdgeType("mule:uses-connector");
    public static final EdgeType REFERENCES_DWL = new EdgeType("mule:references-dwl");

    @Override
    public Map<String, NodeType> nodeTypes() {
        return Map.ofEntries(
                Map.entry("MULE_FLOW", FLOW),
                Map.entry("MULE_SUB_FLOW", SUB_FLOW),
                Map.entry("MULE_CONNECTOR", CONNECTOR),
                Map.entry("MULE_ENDPOINT", ENDPOINT),
                Map.entry("MULE_PROCESSOR", PROCESSOR),
                Map.entry("MULE_TRANSFORM", TRANSFORM),
                Map.entry("MULE_ERROR_HANDLER", ERROR_HANDLER),
                Map.entry("DATAWEAVE_SCRIPT", DATAWEAVE_SCRIPT));
    }

    @Override
    public Map<String, EdgeType> edgeTypes() {
        return Map.ofEntries(
                Map.entry("MULE_FLOW_CONTAINS", FLOW_CONTAINS),
                Map.entry("MULE_CALLS_SUBFLOW", CALLS_SUBFLOW),
                Map.entry("MULE_USES_CONNECTOR", USES_CONNECTOR),
                Map.entry("MULE_REFERENCES_DWL", REFERENCES_DWL));
    }

    @Override
    public int summaryOrder() {
        // Keep the historical Mule-first summary for mixed migration projects.
        return -1;
    }

    @Override
    public Optional<String> summarize(ProjectGraph graph) {
        int flows = graph.findByType(FLOW).size();
        if (flows > 0) {
            int subFlows = graph.findByType(SUB_FLOW).size();
            int dwl = graph.findByType(DATAWEAVE_SCRIPT).size();
            String muleVersion = RuntimeDetector.findMavenProperty(graph, "mule.version");
            if (muleVersion == null)
                muleVersion = RuntimeDetector.findMavenProperty(graph, "app.runtime");
            if (muleVersion == null)
                muleVersion = RuntimeDetector.findArtifactVersion(graph, "org.mule.runtime", "mule-core");
            if (muleVersion == null)
                muleVersion = RuntimeDetector.findArtifactVersion(graph, "org.mule", "mule-core");
            return Optional.of("MuleSoft Mule" + (muleVersion != null ? " " + muleVersion : "")
                               + " (" + flows + " flows, " + subFlows + " sub-flows"
                               + (dwl > 0 ? ", " + dwl + " DataWeave scripts" : "") + ")");
        }
        return Optional.empty();
    }
}
