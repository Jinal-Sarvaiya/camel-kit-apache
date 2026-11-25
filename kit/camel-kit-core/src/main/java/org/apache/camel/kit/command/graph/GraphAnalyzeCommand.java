package org.apache.camel.kit.command.graph;

import org.apache.camel.kit.graph.ProjectGraph;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;

@CommandDefinition(name = "analyze", description = "Summarize a graph with a selected migration adapter")
public class GraphAnalyzeCommand extends GraphQueryCommand {
    @Argument(required = true, index = "0", description = "Graph adapter namespace")
    String namespace;

    @Override
    protected String execute(ProjectGraph graph) {
        var adapter = types.requireAdapter(namespace);
        var result = GraphJsonWriter.createObject();
        result.put("adapter", namespace);
        try {
            var summary = adapter.summarize(graph);
            result.put("matched", summary.isPresent());
            summary.ifPresent(text -> result.put("summary", text));
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException("Graph summary failed for provider/" + namespace + ": " + e, e);
        }
        return GraphJsonWriter.toJson(result);
    }
}
