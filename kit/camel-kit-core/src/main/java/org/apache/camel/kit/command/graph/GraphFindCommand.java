package org.apache.camel.kit.command.graph;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.model.NodeType;
import org.apache.camel.kit.graph.query.GraphQuery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;

@CommandDefinition(name = "find", description = "Find nodes by type and name pattern")
public class GraphFindCommand extends GraphQueryCommand {
    @Option(parser = KitOptionParser.class, name = "type",
            description = "Node type filter")
    String type;
    @Option(parser = KitOptionParser.class, name = "name",
            description = "Name pattern (regex)", defaultValue = ".*")
    String namePattern;

    @Override
    protected String execute(ProjectGraph graph) {
        NodeType nodeType = null;
        if (type != null) {
            try {
                nodeType = NodeType.valueOf(type);
            } catch (IllegalArgumentException e) {
                ObjectNode error = GraphJsonWriter.createObject();
                error.put("error", "Invalid node type: " + type);
                error.put("validTypes",
                        Arrays.stream(NodeType.values()).map(Enum::name).collect(Collectors.joining(", ")));
                return GraphJsonWriter.toJson(error);
            }
        }
        GraphQuery query = new GraphQuery(graph);
        List<GraphNode> nodes = query.find(namePattern, nodeType);
        ObjectNode root = GraphJsonWriter.createObject();
        root.put("found", !nodes.isEmpty());
        root.put("total", nodes.size());
        root.set("nodes", GraphJsonWriter.nodesToArray(nodes));
        return GraphJsonWriter.toJson(root);
    }
}
