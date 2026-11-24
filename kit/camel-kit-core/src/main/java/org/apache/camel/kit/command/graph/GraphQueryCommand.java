package org.apache.camel.kit.command.graph;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.graph.GraphSerializer;
import org.apache.camel.kit.graph.GraphTypeRegistry;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.workflow.SkillCatalog;

import org.aesh.command.option.Option;
import org.aesh.command.option.OptionVisibility;

public abstract class GraphQueryCommand extends CliCommand {

    @Option(parser = KitOptionParser.class, name = "graph-file",
            description = "Path to project graph JSON",
            defaultValue = ".camel-kit/project-graph.json",
            visibility = OptionVisibility.HIDDEN,
            converter = PathConverter.class)
    protected Path graphFile;

    protected GraphTypeRegistry types;

    @Override
    public Integer call() {
        if (!Files.exists(graphFile)) {
            err("Graph not found: " + graphFile + ". Run 'graph generate' first.");
            return 1;
        }
        try {
            types = SkillCatalog.bundled().graphTypes();
            ProjectGraph graph;
            try {
                graph = GraphSerializer.read(graphFile, types);
            } catch (IOException e) {
                err("Invalid graph file: " + graphFile + ". " + errorMessage(e)
                    + ". Regenerate with 'graph generate'.");
                return 1;
            }
            out(execute(graph));
            return 0;
        } catch (Exception | LinkageError e) {
            err(errorMessage(e));
            return 1;
        }
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }

    protected static String invalidType(String kind, String value, Set<String> names) {
        var error = GraphJsonWriter.createObject();
        error.put("error", "Invalid " + kind + " type: " + value);
        error.put("validTypes", String.join(", ", names));
        return GraphJsonWriter.toJson(error);
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
