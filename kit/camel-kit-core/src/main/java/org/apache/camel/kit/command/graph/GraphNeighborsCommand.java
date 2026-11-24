package org.apache.camel.kit.command.graph;

import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.EdgeType;
import org.apache.camel.kit.graph.query.GraphQuery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "neighbors", description = "BFS traversal from a node")
public class GraphNeighborsCommand extends GraphQueryCommand {
    @Argument(required = true, index = "0", description = "Node ID")
    String nodeId;
    @Option(parser = KitOptionParser.class, name = "direction", defaultValue = "both")
    String direction;
    @Option(parser = KitOptionParser.class, name = "edge-type")
    String edgeType;
    @Option(parser = KitOptionParser.class, name = "depth", defaultValue = "1")
    int depth;

    @Override
    protected String execute(ProjectGraph graph) {
        GraphQuery query = new GraphQuery(graph);
        EdgeType et = null;
        if (edgeType != null) {
            try {
                et = types.edgeType(edgeType);
            } catch (IllegalArgumentException e) {
                ObjectNode error = GraphJsonWriter.createObject();
                error.put("error", "Invalid edge type: " + edgeType);
                error.put("validTypes",
                        String.join(", ", types.edgeNames()));
                return GraphJsonWriter.toJson(error);
            }
        }
        GraphQuery.NeighborResult result = query.neighbors(nodeId, direction, et, depth);
        ObjectNode root = GraphJsonWriter.createObject();
        root.put("found", !result.nodes().isEmpty());
        root.put("total", result.nodes().size());
        root.set("nodes", GraphJsonWriter.nodesToArray(result.nodes()));
        root.set("edges", GraphJsonWriter.edgesToArray(result.edges()));
        return GraphJsonWriter.toJson(root);
    }
}
