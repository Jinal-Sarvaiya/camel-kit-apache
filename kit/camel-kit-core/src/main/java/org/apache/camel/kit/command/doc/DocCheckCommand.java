package org.apache.camel.kit.command.doc;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.doc.DocumentationEvidence;
import org.apache.camel.kit.doc.FrontmatterHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "check", description = "Check document staleness status")
public class DocCheckCommand extends CliCommand {

    @Argument(required = true, index = "0", description = "Path to the document to check",
              converter = PathConverter.class)
    Path file;

    @Option(parser = KitOptionParser.class, name = "project",
            description = "Also check recorded review inputs against this project; paths are project-relative",
            converter = PathConverter.class)
    Path project;

    @Override
    public Integer call() {
        PrintWriter out = this.out;
        PrintWriter err = this.err;

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
