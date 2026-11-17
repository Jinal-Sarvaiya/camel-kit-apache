package org.apache.camel.kit.command.doc;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.doc.FrontmatterHandler;
import org.apache.camel.kit.doc.GeneratedInfo;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(name = "stale", description = "Mark a document as stale")
public class DocStaleCommand extends CliCommand {

    @Argument(required = true, index = "0", description = "Path to the document to mark stale",
              converter = PathConverter.class)
    Path file;

    @Option(parser = KitOptionParser.class, name = "reason", required = true,
            description = "Reason for staleness")
    String reason;

    @Option(parser = KitOptionParser.class, name = "cascade",
            description = "Propagate staleness to downstream artifacts", hasValue = false)
    boolean cascade;

    @Override
    public Integer call() {
        PrintWriter err = this.err;

        if (!Files.exists(file)) {
            err.println("Error: file not found: " + file);
            err.flush();
            return 1;
        }

        if (reason == null || reason.isBlank()) {
            err.println("Error: --reason must not be blank");
            err.flush();
            return 1;
        }

        String since = Instant.now().toString();

        try {
            markFileStale(file, reason, since);
            err.println("Marked stale: " + file);

            if (cascade) {
                cascadeStale(file, since, err);
            }

            err.flush();
            return 0;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            err.flush();
            return 1;
        }
    }

    private void cascadeStale(Path origin, String since, PrintWriter err) throws IOException {
        Path dir = origin.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }

        Set<String> visited = new HashSet<>();
        visited.add(origin.toAbsolutePath().normalize().toString());

        Deque<String> queue = new ArrayDeque<>();
        queue.add(origin.getFileName().toString());

        while (!queue.isEmpty()) {
            String sourceFilename = queue.poll();

            try (Stream<Path> siblings = Files.list(dir)) {
                siblings
                        .filter(p -> p.toString().endsWith(".md"))
                        .filter(p -> !visited.contains(p.toAbsolutePath().normalize().toString()))
                        .forEach(sibling -> {
                            try {
                                String content = Files.readString(sibling);
                                String yaml = FrontmatterHandler.extractFrontmatterYaml(content);
                                GeneratedInfo gen = FrontmatterHandler.parseGenerated(yaml);

                                if (gen != null && sourceFilename.equals(gen.getFrom())) {
                                    String cascadeReason = "upstream `" + sourceFilename + "` was marked stale";
                                    markFileStale(sibling, cascadeReason, since);
                                    err.println("Marked stale (cascade): " + sibling.getFileName());
                                    visited.add(sibling.toAbsolutePath().normalize().toString());
                                    queue.add(sibling.getFileName().toString());
                                }
                            } catch (IOException e) {
                                err.println(
                                        "Warning: could not process " + sibling.getFileName() + ": " + e.getMessage());
                            }
                        });
            }
        }
    }

    static void markFileStale(Path path, String reason, String since) throws IOException {
        String content = Files.readString(path);
        String updated = FrontmatterHandler.markStale(content, reason, since);
        Files.writeString(path, updated);
    }
}
