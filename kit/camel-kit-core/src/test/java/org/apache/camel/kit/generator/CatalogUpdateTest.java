package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.output.Printer;
import org.apache.camel.kit.output.SystemPrinter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CatalogUpdateTest {

    @Test
    @Tag("cli-compat")
    void releaseHashesFollowTheSharedSkillsDirectory() throws Exception {
        String shared = ".agents/skills/camel-test/SKILL.md";
        var hashes = ReleasedCatalog.forAgent("codex").get(shared);
        assertEquals(hashes, ReleasedCatalog.forAgent("antigravity").get(shared));
        // The three released 0.4.1 renderings of the same skill.
        assertTrue(hashes.contains("7382de1bc3eac55b2169371d80bf11714a9a06bccac3d4e7b0b42736bab5a230"));
        assertTrue(hashes.contains("f1c3e3ca8409e28665ada0527f8eb0ea2ceae491318dbd849700b735cb28ad61"));
        String claudeHash = "b55574a4b7717bfe730ec71daf8aa5e75d6ed855b1a9dc3a5a86f01b25d0d970";
        assertTrue(ReleasedCatalog.forAgent("claude").get(".claude/skills/camel-test/SKILL.md").contains(claudeHash));
        assertFalse(hashes.contains(claudeHash));
    }

    @Test
    @Tag("cli-compat")
    void queryingRetiredCaseAliasesDoesNotAlterTheLedger(@TempDir Path project) throws Exception {
        Path folder = Files.createDirectories(project.resolve(".claude/skills/camel-start"));
        Path canonical = Files.writeString(folder.resolve("SKILL.md"), "Installed content");
        Path previous = folder.resolve("Skill.md");
        if (!Files.exists(previous)) {
            Files.createLink(previous, canonical);
        }
        Files.createDirectories(project.resolve(".camel-kit"));
        var files = ManagedFiles.open(project, Set.of());
        files.recordCatalogFile(previous, Files.readAllBytes(previous), ManagedFiles.Action.CREATE);
        files.save();
        String original = Files.readString(project.resolve(ManagedFiles.LEDGER));
        assertTrue(files.retiredEntryPoints("claude").isEmpty());
        assertTrue(files.matchesCatalogFile(files.key(canonical), canonical));
        files.save();
        assertEquals(original, Files.readString(project.resolve(ManagedFiles.LEDGER)));
    }

    @Test
    void editDuringRenderingPreservesLiveBytesAndProposesTheCompletedBinary(@TempDir Path project) throws Exception {
        var agent = AgentRegistry.get("bob2");
        String path = agent.skillsDirectory() + "/shared/example.bin";
        Path target = project.resolve(path);
        Files.createDirectories(target.getParent());
        Path ledger = project.resolve(ManagedFiles.LEDGER);
        Files.createDirectories(ledger.getParent());
        var files = ManagedFiles.open(project, Set.of());
        var ctx = new InitContext(
                agent, "bob2", project.resolve(agent.commandDirectory()), project.resolve(agent.skillsDirectory()),
                project, "camel-kit", DistributionConfig.loadFromClasspathOrDefaults(), false, Printer.noop(), files);
        var update = new CatalogUpdate(ctx, Set.of(path), List.of(), Map.of());
        byte[] incoming = {0, (byte) 0xff, '\r', '\n'};
        byte[] personal = {0, (byte) 0xfe, '\n'};
        assertEquals(ManagedFiles.Action.CREATE, update.changes().getFirst().action());
        update.generate(install -> {
            Path rendered = install.skillFile("shared/example.bin");
            Files.createDirectories(rendered.getParent());
            Files.write(rendered, incoming);
            Files.write(target, personal);
        });
        files.save();
        assertArrayEquals(personal, Files.readAllBytes(target));
        assertArrayEquals(incoming, Files
                .readAllBytes(project.resolve(".camel-kit/conflicts/catalog/bob/skills/shared/example.bin.proposed")));
        assertFalse(Files.readString(ledger).contains("catalog/" + path));
    }

    @Test
    void editAfterLayoutSwapKeepsTheGeneratedOwnershipHash(@TempDir Path project) throws Exception {
        var agent = AgentRegistry.get("bob2");
        Path skills = project.resolve(agent.skillsDirectory());
        String path = agent.skillsDirectory() + "/shared/layout";
        Path old = project.resolve(path).resolve("old.md");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "Before");
        Files.createDirectories(project.resolve(".camel-kit"));
        var files = ManagedFiles.open(project, Set.of());
        files.recordCatalogFile(old, Files.readAllBytes(old), ManagedFiles.Action.CREATE);
        var printer = new SystemPrinter() {
            @Override
            public void println(String line) {
                if (line.contains("Replaced catalog layout")) {
                    assertDoesNotThrow(() -> Files.writeString(project.resolve(path), "Team edit"));
                }
            }
        };
        var ctx = new InitContext(
                agent, "bob2", project.resolve(agent.commandDirectory()), skills, project,
                "camel-kit", DistributionConfig.loadFromClasspathOrDefaults(), false, printer, files);
        var update = new CatalogUpdate(
                ctx, Set.of(path),
                List.of(new ManagedFiles.Change(files.key(old), ManagedFiles.Action.REMOVE, null)), Map.of());
        update.generate(install -> {
            Path target = install.skillFile("shared/layout");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "After");
        });

        assertEquals("Team edit", Files.readString(project.resolve(path)));
        assertEquals(ManagedFiles.Action.PRESERVE,
                files.planCatalogFile(project.resolve(path), "After".getBytes(StandardCharsets.UTF_8)).action());
    }

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
        var plan = new CatalogUpdate(ctx, Set.of(first + "/guide.md", second + "/guide.md"), retired, Map.of());

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
