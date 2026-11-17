package org.apache.camel.kit.command.graph;

import java.util.List;

import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.query.GraphQuery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "path", description = "Find shortest path between two nodes")
public class GraphPathCommand extends GraphQueryCommand {
    @Argument(required = true, index = "0")
    String fromId;
    @Argument(required = true, index = "1")
    String toId;
    @Option(parser = KitOptionParser.class, name = "max-depth", defaultValue = "5")
    int maxDepth;

    @Override
    protected String execute(ProjectGraph graph) {
        List<GraphNode> path = new GraphQuery(graph).path(fromId, toId, maxDepth);
        ObjectNode root = GraphJsonWriter.createObject();
        if (path.isEmpty()) {
            root.put("found", false);
            root.put("message", "No path found between " + fromId + " and " + toId);
        } else {
            root.put("found", true);
            root.put("length", path.size());
            root.set("path", GraphJsonWriter.nodesToArray(path));
        }
        return GraphJsonWriter.toJson(root);
    }
}
