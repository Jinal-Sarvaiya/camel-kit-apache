package org.apache.camel.kit.command.doc;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.doc.FrontmatterHandler;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;

@CommandDefinition(name = "unstale", description = "Clear staleness from a document")
public class DocUnstaleCommand extends CliCommand {

    @Argument(required = true, index = "0", description = "Path to the document to clear",
              converter = PathConverter.class)
    Path file;

    @Override
    public Integer call() {
        PrintWriter err = this.err;

        if (!Files.exists(file)) {
            err.println("Error: file not found: " + file);
            err.flush();
            return 1;
        }

        try {
            String content = Files.readString(file);
            String updated = FrontmatterHandler.clearStale(content);
            Files.writeString(file, updated);
            err.println("Cleared staleness: " + file);
            err.flush();
            return 0;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            err.flush();
            return 1;
        }
    }
}
