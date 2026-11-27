package org.apache.camel.kit.graph.adapter.biztalk;

import java.util.Map;
import java.util.Optional;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.*;

public final class BizTalkGraphAdapter implements GraphTypeAdapter {
    public static final NodeType ORCHESTRATION = new NodeType("biztalk:orchestration");
    public static final NodeType SHAPE = new NodeType("biztalk:shape");
    public static final NodeType MAP = new NodeType("biztalk:map");
    public static final NodeType FUNCTOID = new NodeType("biztalk:functoid");
    public static final NodeType SCHEMA = new NodeType("biztalk:schema");
    public static final NodeType PIPELINE = new NodeType("biztalk:pipeline");
    public static final NodeType PIPELINE_COMPONENT = new NodeType("biztalk:pipeline-component");
    public static final NodeType PORT = new NodeType("biztalk:port");
    public static final NodeType ADAPTER = new NodeType("biztalk:adapter");
    public static final NodeType MESSAGE = new NodeType("biztalk:message");
    public static final EdgeType ORCHESTRATION_CONTAINS = new EdgeType("biztalk:orchestration-contains");
    public static final EdgeType USES_MAP = new EdgeType("biztalk:uses-map");
    public static final EdgeType USES_SCHEMA = new EdgeType("biztalk:uses-schema");
    public static final EdgeType CALLS_ORCHESTRATION = new EdgeType("biztalk:calls-orchestration");
    public static final EdgeType PORT_BINDING = new EdgeType("biztalk:port-binding");
    public static final EdgeType FUNCTOID_CHAIN = new EdgeType("biztalk:functoid-chain");
    public static final EdgeType PIPELINE_STAGE = new EdgeType("biztalk:pipeline-stage");

    @Override
    public Map<String, NodeType> nodeTypes() {
        return Map.ofEntries(
                Map.entry("BIZTALK_ORCHESTRATION", ORCHESTRATION),
                Map.entry("BIZTALK_SHAPE", SHAPE),
                Map.entry("BIZTALK_MAP", MAP),
                Map.entry("BIZTALK_FUNCTOID", FUNCTOID),
                Map.entry("BIZTALK_SCHEMA", SCHEMA),
                Map.entry("BIZTALK_PIPELINE", PIPELINE),
                Map.entry("BIZTALK_PIPELINE_COMPONENT", PIPELINE_COMPONENT),
                Map.entry("BIZTALK_PORT", PORT),
                Map.entry("BIZTALK_ADAPTER", ADAPTER),
                Map.entry("BIZTALK_MESSAGE", MESSAGE));
    }

    @Override
    public Map<String, EdgeType> edgeTypes() {
        return Map.ofEntries(
                Map.entry("BIZTALK_ORCHESTRATION_CONTAINS", ORCHESTRATION_CONTAINS),
                Map.entry("BIZTALK_USES_MAP", USES_MAP),
                Map.entry("BIZTALK_USES_SCHEMA", USES_SCHEMA),
                Map.entry("BIZTALK_CALLS_ORCHESTRATION", CALLS_ORCHESTRATION),
                Map.entry("BIZTALK_PORT_BINDING", PORT_BINDING),
                Map.entry("BIZTALK_FUNCTOID_CHAIN", FUNCTOID_CHAIN),
                Map.entry("BIZTALK_PIPELINE_STAGE", PIPELINE_STAGE));
    }

    @Override
    public Optional<String> summarize(ProjectGraph graph) {
        int orchs = graph.findByType(ORCHESTRATION).size();
        int maps = graph.findByType(MAP).size();
        int pipelines = graph.findByType(PIPELINE).size();
        if (orchs > 0 || maps > 0 || pipelines > 0) {
            return Optional.of("Microsoft BizTalk (" + orchs + " orchestrations"
                               + (maps > 0 ? ", " + maps + " maps" : "")
                               + (pipelines > 0 ? ", " + pipelines + " pipelines" : "") + ")");
        }
        return Optional.empty();
    }
}
