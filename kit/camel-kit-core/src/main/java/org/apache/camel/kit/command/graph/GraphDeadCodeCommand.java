package org.apache.camel.kit.command.graph;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.query.DeadCodeAnalyzer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;

@CommandDefinition(name = "dead-code", description = "Report graph-covered structural retirement candidates")
public class GraphDeadCodeCommand extends GraphQueryCommand {

    @Override
    protected String execute(ProjectGraph graph) {
        DeadCodeAnalyzer analyzer = new DeadCodeAnalyzer(graph);
        DeadCodeAnalyzer.DeadCodeResult result = analyzer.analyze();

        ObjectNode root = GraphJsonWriter.createObject();
        root.put("available", true);
        root.set("unusedArtifacts", GraphJsonWriter.nodesToArray(result.unusedArtifacts()));
        root.set("orphanedRoutes", GraphJsonWriter.nodesToArray(result.orphanedRoutes()));
        root.set("unusedProperties", GraphJsonWriter.nodesToArray(result.unusedProperties()));

        ObjectNode summary = root.putObject("summary");
        summary.put("unusedArtifacts", result.unusedArtifacts().size());
        summary.put("orphanedRoutes", result.orphanedRoutes().size());
        summary.put("unusedProperties", result.unusedProperties().size());

        return GraphJsonWriter.toJson(root);
    }
}
