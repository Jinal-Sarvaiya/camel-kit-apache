package org.apache.camel.kit.command.graph;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.graph.GraphSerializer;
import org.apache.camel.kit.graph.ProjectGraph;

import org.aesh.command.option.Option;

public abstract class GraphQueryCommand extends CliCommand {

    @Option(parser = KitOptionParser.class, name = "graph-file",
            description = "Path to project graph JSON",
            defaultValue = ".camel-kit/project-graph.json",
            visibility = org.aesh.command.option.OptionVisibility.HIDDEN,
            converter = PathConverter.class)
    protected Path graphFile;

    @Override
    public Integer call() {
        if (!Files.exists(graphFile)) {
            err("Graph not found: " + graphFile + ". Run 'graph generate' first.");
            return 1;
        }
        try {
            ProjectGraph graph = GraphSerializer.read(graphFile);
            out(execute(graph));
            return 0;
        } catch (Exception e) {
            err("Invalid graph file: " + graphFile + ". Regenerate with 'graph generate'.");
            return 1;
        }
    }

    protected abstract String execute(ProjectGraph graph) throws Exception;

    protected void out(String message) {
        PrintWriter w = this.out;
        w.println(message);
        w.flush();
    }

    protected void err(String message) {
        PrintWriter w = this.err;
        w.println(message);
        w.flush();
    }
}
