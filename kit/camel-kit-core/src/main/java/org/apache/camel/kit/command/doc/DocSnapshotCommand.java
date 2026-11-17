package org.apache.camel.kit.command.doc;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.camel.kit.doc.DocumentationEvidence;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "snapshot", description = "Capture source inventory before a documentation review (JSON)")
public class DocSnapshotCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, description = "Project root")
    Path project;

    @Option(names = "--source", required = true,
            description = "Source file or directory, relative to project; repeatable")
    List<String> sources;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        try {
            spec.commandLine().getOut()
                    .println(DocumentationEvidence.json(DocumentationEvidence.snapshot(project, sources)));
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Error capturing sources: " + e.getMessage());
            return 1;
        }
    }
}
