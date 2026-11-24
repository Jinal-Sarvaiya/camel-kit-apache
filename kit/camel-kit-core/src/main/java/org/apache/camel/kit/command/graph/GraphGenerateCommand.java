package org.apache.camel.kit.command.graph;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.graph.GraphBuildResult;
import org.apache.camel.kit.graph.GraphSerializer;
import org.apache.camel.kit.graph.ParserDiagnostic;
import org.apache.camel.kit.workflow.MigrationParsers;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionVisibility;

@CommandDefinition(name = "generate", description = "Generate or rebuild the project graph from source files")
public class GraphGenerateCommand extends CliCommand {
    @Option(parser = KitOptionParser.class, name = "output", shortName = 'o',
            defaultValue = ".camel-kit/project-graph.json",
            converter = PathConverter.class)
    Path output;
    @Option(parser = KitOptionParser.class, name = "project-dir", defaultValue = ".",
            visibility = OptionVisibility.HIDDEN,
            converter = PathConverter.class)
    Path projectDir;
    @Option(parser = KitOptionParser.class, name = "require-parser",
            description = "Require the named migration parser provider in this distribution")
    String requiredParser;

    @Override
    public Integer call() {
        try {
            Path root = projectDir.toAbsolutePath().normalize();
            PrintWriter err = this.err;
            GraphBuildResult result = MigrationParsers.newGraphBuilder(requiredParser).buildWithDiagnostics(root);
            for (ParserDiagnostic diagnostic : result.warningDiagnostics()) {
                err.println("Warning: " + diagnostic.summary());
            }
            if (!result.successful()) {
                for (ParserDiagnostic diagnostic : result.failedDiagnostics()) {
                    err.println("Error: " + diagnostic.summary());
                }
                err.flush();
                return 1;
            }
            if (result.graph().nodeCount() == 0) {
                err.println("Warning: No source files found");
                err.flush();
            }
            Path outPath = output.isAbsolute() ? output : root.resolve(output);
            Files.createDirectories(outPath.getParent());
            GraphSerializer.write(result.graph(), outPath, root.toString());
            err.println("Graph built: " + result.graph().nodeCount() + " nodes, "
                        + result.graph().edgeCount() + " edges");
            err.println("Written to: " + outPath);
            err.flush();
            return 0;
        } catch (Exception e) {
            this.err.println("Error: " + e.getMessage());
            this.err.flush();
            return 1;
        }
    }
}
