package org.apache.camel.kit.command.doc;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.apache.camel.kit.doc.DocumentationEvidence;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "record",
         description = "Record a completed, partial or failed documentation review; does not perform the review")
public class DocRecordCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Document path, relative to project")
    Path file;

    @Option(names = "--project", required = true, description = "Project root")
    Path project;

    @Option(names = "--snapshot", required = true, description = "JSON from doc snapshot, relative to project")
    Path snapshot;

    @Option(names = "--evidence", required = true, description = "Review report path, relative to project")
    Path evidence;

    @Option(names = "--result", required = true, description = "complete, partial or failed")
    String result;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        try {
            DocumentationEvidence.record(project, file, snapshot, evidence, result);
            spec.commandLine().getOut().println("Recorded documentation review: " + file + " (" + result + ")");
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("Error recording review: " + e.getMessage());
            return 1;
        }
    }
}
