package org.apache.camel.kit.command.graph;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.graph.GraphSerializer;
import org.apache.camel.kit.graph.GraphVisualizer;
import org.apache.camel.kit.graph.ProjectGraph;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;

@CommandDefinition(name = "visualize", description = "Generate interactive HTML graph visualization")
public class GraphVisualizeCommand extends CliCommand {
    @Option(parser = KitOptionParser.class, name = "input", shortName = 'i',
            defaultValue = ".camel-kit/project-graph.json",
            converter = PathConverter.class)
    Path input;
    @Option(parser = KitOptionParser.class, name = "output", shortName = 'o',
            defaultValue = ".camel-kit/project-graph.html",
            converter = PathConverter.class)
    Path output;
    @Option(parser = KitOptionParser.class, name = "library", shortName = 'l',
            description = "Visualization library: cytoscape, d3, vis-network, antv-g6",
            defaultValue = "cytoscape", completer = LibCandidates.class)
    String library;

    public static class LibCandidates
            implements org.aesh.command.completer.OptionCompleter<org.aesh.command.completer.CompleterInvocation> {
        public void complete(org.aesh.command.completer.CompleterInvocation invocation) {
            invocation.addAllCompleterValues(List.of("cytoscape", "d3", "vis-network", "antv-g6"));
        }
    }

    @Override
    public Integer call() {
        PrintWriter err = this.err;
        if (!Files.exists(input)) {
            err.println("Graph not found: " + input + ". Run 'graph generate' first.");
            err.flush();
            return 1;
        }
        try {
            ProjectGraph graph = GraphSerializer.read(input);
            err.println("Reading: " + input + " (" + graph.nodeCount() + " nodes, " + graph.edgeCount() + " edges)");
            String html = GraphVisualizer.generate(graph, library);
            Files.createDirectories(output.getParent());
            Files.writeString(output, html);
            err.println("Written to: " + output + " (library: " + library + ")");
            err.flush();
            return 0;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            err.flush();
            return 1;
        }
    }
}
