package org.apache.camel.kit.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.output.Printer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class InitWriteConfinementTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {
            ".claude/worktrees/existing/.idea/icon.png", "test/data/example", "schemas/example",
            "docs/flows/example", ".camel-kit/ship/state/example", ".mvn/wrapper/unrelated",
            ".claude/skills/custom/example", ".claude/agents/custom.md", ".claude/commands/custom.md"})
    void unrelatedDescendantLinksDoNotPreventInitialization(String relative) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        Path link = project.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, sentinel);

        new InitService().initialize(request(project, "claude"));

        assertTrue(Files.isRegularFile(project.resolve("CLAUDE.md")));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("untouched", Files.readString(sentinel));
    }

    @ParameterizedTest
    @ValueSource(strings = {"antigravity", "bob2", "claude", "codex", "copilot", "opencode", "pi", "qwen"})
    void unrelatedAgentAssetsMayContainLinks(String agent) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        var config = AgentRegistry.get(agent);
        Path custom = project.resolve(config.skillsDirectory()).resolve("custom/example");
        Path mcpConfig = Path.of(config.mcpConfigPath());
        Path agentRoot = mcpConfig.getNameCount() > 1 ? project.resolve(mcpConfig.getName(0)) : project;
        Path worktree = agentRoot.resolve("worktrees/example");
        for (Path link : List.of(custom, worktree)) {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, sentinel);
        }
        new InitService().initialize(request(project, agent));
        assertTrue(Files.isRegularFile(project.resolve("AGENTS.md")));
        assertTrue(Files.isSymbolicLink(custom));
        assertTrue(Files.isSymbolicLink(worktree));
        assertEquals("untouched", Files.readString(sentinel));
    }

    @ParameterizedTest
    @ValueSource(strings = {".camel-kit", ".camel-kit/templates", "docs", ".mvn", ".mvn/wrapper"})
    void generatedDirectoriesCannotRedirectWrites(String relative) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "untouched");
        Path link = project.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);

        assertThrows(IOException.class, () -> new InitService().initialize(request(project, "bob2")));

        assertEquals("untouched", Files.readString(sentinel));
        try (var paths = Files.list(outside)) {
            assertEquals(List.of(sentinel), paths.toList(), "No generated files may be written through the link");
        }
        assertTrue(Files.isSymbolicLink(link));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".camel-kit/config.properties", ".camel-kit/templates/flow.md", ".camel-kit/project-graph.json",
            "docs/constitution.md",
            ".mvn/wrapper/maven-wrapper.properties", "mvnw", "mvnw.cmd", "AGENTS.md"})
    void generatedFilesCannotOverwriteOutsideSymlinkTargets(String relative) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        Path link = project.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, sentinel);

        assertThrows(IOException.class, () -> new InitService().initialize(request(project, "bob2")));

        assertEquals("untouched", Files.readString(sentinel));
        assertTrue(Files.isSymbolicLink(link));
    }

    @ParameterizedTest
    @ValueSource(strings = {".camel-kit/config.properties", "mvnw", "AGENTS.md"})
    void generatedFilesReplaceHardLinksWithoutChangingOutsideContent(String relative) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        Path generated = project.resolve(relative);
        Files.createDirectories(generated.getParent());
        Files.createLink(generated, sentinel);

        new InitService().initialize(request(project, "bob2"));

        assertEquals("untouched", Files.readString(sentinel));
        assertTrue(Files.isRegularFile(generated));
        assertNotEquals("untouched", Files.readString(generated));
        assertFalse(Files.isSameFile(generated, sentinel), "Generation must replace the selected directory entry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"antigravity", "bob2", "claude", "codex", "copilot", "opencode", "pi", "qwen"})
    void nestedSkillDirectoryCannotRedirectAnyAgentWrites(String agent) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("context-authority.md"), "untouched");
        Path link = project.resolve(AgentRegistry.get(agent).skillsDirectory()).resolve("shared");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);

        assertThrows(IOException.class, () -> new InitService().initialize(request(project, agent)));

        assertEquals("untouched", Files.readString(sentinel), agent);
        try (var paths = Files.list(outside)) {
            assertEquals(List.of(sentinel), paths.toList(), agent);
        }
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void explicitlySelectedTargetMayBeASymlink() throws Exception {
        Path actual = Files.createDirectory(tempDir.resolve("actual"));
        Path selected = Files.createSymbolicLink(tempDir.resolve("selected"), actual);

        new InitService().initialize(request(selected, "bob2"));

        assertTrue(Files.isRegularFile(actual.resolve(".camel-kit/config.properties")));
        assertTrue(Files.isRegularFile(actual.resolve("AGENTS.md")));
        assertTrue(Files.isSymbolicLink(selected));
    }

    private InitRequest request(Path project, String agent) {
        return new InitRequest(
                "orders", agent, project, "default", true, false, "auto", "camel-kit", "5.0.1",
                CamelKitMain.distribution(), Printer.noop(), InitProgress.noop(), InitReporter.noop());
    }
}
