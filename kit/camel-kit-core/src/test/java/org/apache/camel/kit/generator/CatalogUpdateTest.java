package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.output.Printer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CatalogUpdateTest {

    @Test
    void aFailedSecondLayoutReplacementRestoresBothOriginals(@TempDir Path project) throws Exception {
        var agent = AgentRegistry.get("bob2");
        Path skills = project.resolve(agent.skillsDirectory());
        Path shared = Files.createDirectories(skills.resolve("shared"));
        String first = agent.skillsDirectory() + "/shared/first";
        String second = agent.skillsDirectory() + "/shared/second";
        String content = "Original\n";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        for (String key : List.of(first, second)) {
            Files.writeString(project.resolve(key), content);
        }
        Path ledger = project.resolve(ManagedFiles.LEDGER);
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "catalog/" + first + "=" + hash + "\ncatalog/" + second + "=" + hash + "\n");
        var ctx = new InitContext(
                agent, "bob2", project.resolve(agent.commandDirectory()), skills, project,
                "camel-kit", DistributionConfig.loadFromClasspathOrDefaults(), false, Printer.noop(),
                ManagedFiles.open(project, Set.of()));
        var retired = List.of(first, second).stream()
                .map(key -> new ManagedFiles.Change(key, ManagedFiles.Action.REMOVE, null)).toList();
        var plan = new CatalogUpdate(ctx, Set.of(first + "/guide.md", second + "/guide.md"), retired);

        IOException failure = assertThrows(IOException.class, () -> plan.generate(install -> {
            Path guide = install.skillFile("shared/first/guide.md");
            Files.createDirectories(guide.getParent());
            Files.writeString(guide, "Replacement");
            // Leave the second replacement missing to exercise a failure after the first swap.
        }));
        assertTrue(failure.getMessage().contains("second"), failure::getMessage);
        for (String key : List.of(first, second)) {
            assertEquals(content, Files.readString(project.resolve(key)));
        }
        try (var entries = Files.list(shared)) {
            assertEquals(2, entries.count());
        }
    }
}
