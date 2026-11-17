package org.apache.camel.kit.command.doc;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.apache.camel.kit.doc.DocumentationEvidence;
import org.apache.camel.kit.doc.FrontmatterHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "check", description = "Check document staleness status")
public class DocCheckCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the document to check")
    Path file;

    @Option(names = "--project",
            description = "Also check recorded review inputs against this project; paths are project-relative")
    Path project;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        try {
            Path document = project == null ? file : DocumentationEvidence.resolve(project, file);
            if (!Files.exists(document)) {
                err.println("Error: file not found: " + file);
                err.flush();
                return 1;
            }

            String content = Files.readString(document);
            String json = FrontmatterHandler.toCheckJson(file.toString(), content);
            if (project != null) {
                var mapper = new ObjectMapper();
                ObjectNode result = (ObjectNode) mapper.readTree(json);
                var status = DocumentationEvidence.check(project, file);
                result.set("reconciliation", mapper.valueToTree(status));
                if (!status.status().equals("unchanged")) {
                    result.put("stale", true);
                    result.put("reason", status.reason());
                    result.putNull("since");
                }
                json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            }
            out.println(json);
            out.flush();
            return 0;
        } catch (Exception e) {
            err.println("Error reading file: " + e.getMessage());
            err.flush();
            return 1;
        }
    }
}
