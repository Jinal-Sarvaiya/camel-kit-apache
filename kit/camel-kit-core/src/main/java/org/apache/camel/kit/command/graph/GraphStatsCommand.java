package org.apache.camel.kit.command.graph;

import java.util.Map;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.query.GraphQuery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;

@CommandDefinition(name = "stats", description = "Show node and edge counts by type")
public class GraphStatsCommand extends GraphQueryCommand {

    @Override
    protected String execute(ProjectGraph graph) {
        GraphQuery query = new GraphQuery(graph);
        Map<String, Integer> statsByType = query.stats();

        ObjectNode root = GraphJsonWriter.createObject();
        root.put("available", true);
        root.put("nodes", graph.nodeCount());
        root.put("edges", graph.edgeCount());

        ObjectNode byType = root.putObject("nodesByType");
        statsByType.forEach(byType::put);

        return GraphJsonWriter.toJson(root);
    }
}
