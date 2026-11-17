package org.apache.camel.kit.command.doc;

import java.nio.file.Path;
import java.util.List;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.doc.DocumentationEvidence;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

@CommandDefinition(name = "snapshot", description = "Capture source inventory before a documentation review (JSON)")
public class DocSnapshotCommand extends CliCommand {
    @Option(parser = KitOptionParser.class, name = "project", required = true,
            description = "Project root",
            converter = PathConverter.class)
    Path project;

    @OptionList(parser = KitOptionParser.class, name = "source", required = true,
                description = "Source file or directory, relative to project; repeatable",
                valueSeparator = OptionList.NO_SEPARATOR)
    List<String> sources;

    @Override
    public Integer call() {
        try {
            this.out
                    .println(DocumentationEvidence.json(DocumentationEvidence.snapshot(project, sources)));
            return 0;
        } catch (Exception e) {
            this.err.println("Error capturing sources: " + e.getMessage());
            return 1;
        }
    }
}
