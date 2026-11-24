package org.apache.camel.kit.command.graph;

import java.util.List;
import java.util.TreeSet;

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
        if (type != null && !types.nodeNames().contains(type)) {
            var names = new TreeSet<>(types.nodeNames());
            graph.getNodes().values().forEach(node -> names.add(node.type().name()));
            if (!names.contains(type)) {
                return invalidType("node", type, names);
            }
        }
        NodeType nodeType = type == null ? null : types.nodeType(type);
        GraphQuery query = new GraphQuery(graph);
        List<GraphNode> nodes = query.find(namePattern, nodeType);
        ObjectNode root = GraphJsonWriter.createObject();
        root.put("found", !nodes.isEmpty());
        root.put("total", nodes.size());
        root.set("nodes", GraphJsonWriter.nodesToArray(nodes));
        return GraphJsonWriter.toJson(root);
    }
}
