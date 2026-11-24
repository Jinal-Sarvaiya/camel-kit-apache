package example;

import java.util.Map;
import java.util.Optional;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.EdgeType;
import org.apache.camel.kit.graph.model.GraphTypeAdapter;
import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.model.GraphEdge;
import org.apache.camel.kit.graph.model.NodeType;

public final class LegacyGraphAdapter implements GraphTypeAdapter {
    static final NodeType FLOW = new NodeType("legacy:flow");
    static final EdgeType DECLARED_IN = new EdgeType("legacy:declared-in");

    record Flow(String name, String source) {
        void addTo(ProjectGraph graph) {
            String flowId = "legacy-flow:" + source;
            String fileId = "legacy-file:" + source;
            graph.addNode(new GraphNode(flowId, FLOW, Map.of("name", name, "file", source)));
            graph.addNode(new GraphNode(fileId, NodeType.RESOURCE_FILE, Map.of("file", source)));
            graph.addEdge(new GraphEdge(flowId, fileId, DECLARED_IN, Map.of()));
        }

        static Flow from(GraphNode node) {
            String name = node.properties().get("name");
            String file = node.properties().get("file");
            if (name == null || name.isBlank() || file == null || file.isBlank()) {
                throw new IllegalArgumentException("Legacy flow " + node.id() + " requires name and file properties");
            }
            return new Flow(name, file);
        }
    }

    @Override
    public Map<String, NodeType> nodeTypes() {
        return Map.of(FLOW.name(), FLOW);
    }

    @Override
    public Map<String, EdgeType> edgeTypes() {
        return Map.of(DECLARED_IN.name(), DECLARED_IN);
    }

    @Override
    public Optional<String> summarize(ProjectGraph graph) {
        var flows = graph.findByType(FLOW);
        if (flows.isEmpty()) {
            return Optional.empty();
        }
        long files = flows.stream().map(Flow::from).map(Flow::source).distinct().count();
        return Optional.of("Legacy flow inventory (flows=" + flows.size() + ", source files=" + files + ")");
    }
}
