package org.apache.camel.kit.command.doc;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.doc.FrontmatterHandler;
import org.apache.camel.kit.doc.GeneratedInfo;
import org.apache.camel.kit.doc.StalenessInfo;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "init", description = "Initialize document with frontmatter metadata")
public class DocInitCommand extends CliCommand {

    @Argument(required = true, index = "0", description = "Path to the document to initialize",
              converter = PathConverter.class)
    Path file;

    @Option(parser = KitOptionParser.class, name = "by", required = true,
            description = "Skill that generated this artifact")
    String by;

    @Option(parser = KitOptionParser.class, name = "from",
            description = "Source artifact this was generated from (omit for root artifacts)")
    String from;

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

            if (FrontmatterHandler.hasFrontmatter(content)) {
                err.println("Already initialized: " + file);
                err.flush();
                return 0;
            }

            GeneratedInfo generated = new GeneratedInfo(Instant.now().toString(), by, from);
            String result = FrontmatterHandler.writeFrontmatter(StalenessInfo.fresh(), generated, content);
            Files.writeString(file, result);

            err.println("Initialized: " + file);
            err.flush();
            return 0;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            err.flush();
            return 1;
        }
    }
}
