package org.apache.camel.kit.command.doc;

import java.nio.file.Path;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.doc.DocumentationEvidence;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "record",
                   description = "Record a completed, partial or failed documentation review; does not perform the review")
public class DocRecordCommand extends CliCommand {
    @Argument(required = true, index = "0", description = "Document path, relative to project",
              converter = PathConverter.class)
    Path file;

    @Option(parser = KitOptionParser.class, name = "project", required = true,
            description = "Project root",
            converter = PathConverter.class)
    Path project;

    @Option(parser = KitOptionParser.class, name = "snapshot", required = true,
            description = "JSON from doc snapshot, relative to project",
            converter = PathConverter.class)
    Path snapshot;

    @Option(parser = KitOptionParser.class, name = "evidence", required = true,
            description = "Review report path, relative to project",
            converter = PathConverter.class)
    Path evidence;

    @Option(parser = KitOptionParser.class, name = "result", required = true,
            description = "complete, partial or failed")
    String result;

    @Override
    public Integer call() {
        try {
            DocumentationEvidence.record(project, file, snapshot, evidence, result);
            this.out.println("Recorded documentation review: " + file + " (" + result + ")");
            return 0;
        } catch (Exception e) {
            this.err.println("Error recording review: " + e.getMessage());
            return 1;
        }
    }
}
