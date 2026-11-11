package org.apache.camel.kit.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.generator.InvalidAgentConfigurationException;
import org.apache.camel.kit.generator.ManagedFiles;
import org.apache.camel.kit.generator.ManagedFiles.Action;
import org.apache.camel.kit.output.Printer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Initialization inside an existing application must not replace files the application already owns, and
 * reinitialization must not discard changes the user made to files Camel-Kit generated.
 */
class InitSafeAdoptionTest {

    /** Every shared path init writes for at least one agent, plus application files it must never touch. */
    private static final Map<String, String> EXISTING_PROJECT = existingProject();

    /** Instruction files that receive Camel-Kit's block next to the user's own content. */
    private static final Set<String> MERGED
            = Set.of("AGENTS.md", "CLAUDE.md", "QWEN.md", ".github/copilot-instructions.md");

    @TempDir
    Path tempDir;

    static List<String> agents() {
        return List.copyOf(AgentRegistry.names());
    }

    @ParameterizedTest
    @MethodSource("agents")
    void adoptionPreservesEveryFileTheProjectAlreadyOwns(String agentName) throws Exception {
        Path project = existingProject(tempDir.resolve(agentName));

        InitResult result = new InitService().initialize(request(project, agentName));

        for (Map.Entry<String, String> file : EXISTING_PROJECT.entrySet()) {
            String actual = Files.readString(project.resolve(file.getKey()));
            if (MERGED.contains(file.getKey())) {
                // The user's text is intact; at most Camel-Kit's block follows it.
                assertTrue(actual.startsWith(file.getValue().stripTrailing()), agentName + " changed " + file.getKey());
                String added = actual.substring(file.getValue().stripTrailing().length()).strip();
                assertTrue(added.isEmpty() || added.startsWith(ManagedFiles.BLOCK_START)
                        && added.endsWith(ManagedFiles.BLOCK_END), agentName + " changed " + file.getKey());
            } else {
                assertEquals(file.getValue(), actual, agentName + " changed " + file.getKey());
            }
        }
        assertTrue(Files.readString(project.resolve("AGENTS.md")).contains("camel-start"), agentName);
        assertTrue(Files.isRegularFile(project.resolve(".camel-kit/config.properties")));
        assertTrue(Files.isDirectory(project.resolve(AgentRegistry.get(agentName).skillsDirectory())));

        List<String> conflicts = result.conflicts().stream().map(ManagedFiles.Change::path).toList();
        assertTrue(conflicts.containsAll(List.of(
                "docs/constitution.md", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")),
                agentName + ": " + conflicts);
        assertFalse(conflicts.contains("AGENTS.md"), agentName + ": " + conflicts);
        // The version Camel-Kit would have written is kept aside so the user can merge it.
        assertTrue(Files.readString(project.resolve(".camel-kit/conflicts/docs/constitution.md"))
                .contains("Constitution"), agentName);
    }

    @ParameterizedTest
    @MethodSource("agents")
    void greenfieldInitializationReportsNoConflicts(String agentName) throws Exception {
        Path project = tempDir.resolve(agentName);

        InitResult result = new InitService().initialize(request(project, agentName));

        assertEquals(List.of(), result.conflicts());
        assertTrue(Files.readString(project.resolve("AGENTS.md")).contains("camel-start"));
        assertTrue(Files.isRegularFile(project.resolve("mvnw")));
        assertTrue(Files.isRegularFile(project.resolve("docs/constitution.md")));
        assertFalse(Files.exists(project.resolve(".camel-kit/conflicts")));
        assertTrue(ledger(project).containsKey("mvnw"));
    }

    @ParameterizedTest
    @MethodSource("agents")
    void reinitializationIsIdempotentForSharedFiles(String agentName) throws Exception {
        Path project = existingProject(tempDir.resolve(agentName));
        new InitService().initialize(request(project, agentName));
        Map<String, String> first = sharedFiles(project);

        InitResult second = new InitService().initialize(request(project, agentName));

        assertEquals(first, sharedFiles(project), agentName);
        assertEquals(1, occurrences(Files.readString(project.resolve("AGENTS.md")), ManagedFiles.BLOCK_START));
        assertFalse(second.conflicts().stream().anyMatch(conflict -> "AGENTS.md".equals(conflict.path())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude", "bob2", "copilot", "pi", "qwen", "antigravity"})
    void existingMcpServersAndSettingsSurviveAdoption(String agentName) throws Exception {
        Path project = tempDir.resolve(agentName);
        Path mcpConfig = project.resolve(AgentRegistry.get(agentName).mcpConfigPath());
        Files.createDirectories(mcpConfig.getParent());
        Files.writeString(mcpConfig, """
                {
                  "theme": "dark",
                  "mcpServers": {
                    "internal-docs": { "command": "docs-server" }
                  }
                }
                """);

        new InitService().initialize(request(project, agentName));

        var config = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(mcpConfig));
        assertEquals("dark", config.path("theme").asText(), agentName);
        assertEquals("docs-server", config.path("mcpServers").path("internal-docs").path("command").asText(),
                agentName);
        assertTrue(config.path("mcpServers").has("camel"), agentName);
        assertTrue(config.path("mcpServers").has("camel-knowledge"), agentName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude", "bob2", "copilot", "pi", "qwen"})
    void unreadableMcpConfigFailsBeforeAnyProjectWrites(String agentName) throws Exception {
        Path project = tempDir.resolve(agentName);
        Path mcpConfig = project.resolve(AgentRegistry.get(agentName).mcpConfigPath());
        Files.createDirectories(mcpConfig.getParent());
        Files.writeString(mcpConfig, "{ not json");
        List<String> before = snapshot(project);

        assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService().initialize(request(project, agentName)));

        assertEquals(before, snapshot(project));
    }

    @Test
    void reinitializationPreservesUserEditsAndRefreshesUntouchedFiles() throws Exception {
        Path project = tempDir.resolve("orders");
        new InitService().initialize(request(project, "claude"));
        Path agentsMd = project.resolve("AGENTS.md");
        String generatedBlock = Files.readString(agentsMd);
        String generatedSettings = Files.readString(project.resolve(".claude/settings.json"));
        Files.writeString(agentsMd, "# Team rules\n\nNever deploy on Fridays.\n\n"
                                    + generatedBlock.replace("camel-start", "edited-inside-the-block")
                                    + "\n## Contacts\n\nintegration@example.invalid\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\nexec ./custom-maven \"$@\"\n");
        Files.writeString(project.resolve(".claude/settings.json"), generatedSettings.replace("\n", "\r\n"));
        Path config = project.resolve(".camel-kit/config.properties");
        Files.writeString(config, Files.readString(config) + "team.owner=integration\n");

        InitResult result = new InitService().initialize(request(project, "claude"));

        // Text around the block survives; the block itself is Camel-Kit's and is regenerated.
        assertEquals("# Team rules\n\nNever deploy on Fridays.\n\n" + generatedBlock
                     + "\n## Contacts\n\nintegration@example.invalid\n",
                Files.readString(agentsMd));
        assertEquals("#!/bin/sh\nexec ./custom-maven \"$@\"\n", Files.readString(project.resolve("mvnw")));
        assertEquals(List.of("mvnw"), result.conflicts().stream().map(ManagedFiles.Change::path).toList());
        // A line-ending conversion is not a user edit: the file is still Camel-Kit's and is regenerated.
        assertEquals(generatedSettings, Files.readString(project.resolve(".claude/settings.json")));
        Properties properties = new Properties();
        try (var in = Files.newInputStream(config)) {
            properties.load(in);
        }
        assertEquals("integration", properties.getProperty("team.owner"));
        assertEquals("claude", properties.getProperty("agent.name"));
    }

    @Test
    void mergeKeepsTheLineEndingsOfTheExistingFile() throws Exception {
        Path project = tempDir.resolve("orders");
        Files.createDirectories(project);
        Files.writeString(project.resolve("AGENTS.md"), "# Team rules\r\n\r\nAsk first.\r\n");

        new InitService().initialize(request(project, "claude"));

        String merged = Files.readString(project.resolve("AGENTS.md"));
        assertTrue(merged.startsWith("# Team rules\r\n\r\nAsk first.\r\n\r\n" + ManagedFiles.BLOCK_START + "\r\n"));
        assertFalse(merged.replace("\r\n", "").contains("\n"), "mixed line endings");
    }

    static List<String> ambiguousBlocks() {
        String start = ManagedFiles.BLOCK_START;
        String end = ManagedFiles.BLOCK_END;
        return List.of(
                // End marker deleted.
                "# Team rules\n\n" + start + "\nhalf a block\n",
                // Start marker deleted.
                "# Team rules\n\nhalf a block\n" + end + "\n",
                // An unterminated block, user text, then a complete block: one span would swallow the user text.
                start + "\nold block\n\n# Team rules\n\nNever deploy on Fridays.\n\n" + start + "\nblock\n" + end
                                                               + "\n",
                // Two complete blocks.
                start + "\none\n" + end + "\n\n# Team rules\n\n" + start + "\ntwo\n" + end + "\n",
                // Markers in the wrong order.
                end + "\n# Team rules\n" + start + "\n");
    }

    @ParameterizedTest
    @MethodSource("ambiguousBlocks")
    void ambiguousBlockMarkersArePreservedInsteadOfGuessedAt(String content) throws Exception {
        Path project = tempDir.resolve("orders");
        Files.createDirectories(project);
        Files.writeString(project.resolve("AGENTS.md"), content);

        InitResult result = new InitService().initialize(request(project, "claude"));

        assertEquals(content, Files.readString(project.resolve("AGENTS.md")));
        assertTrue(result.conflicts().stream().anyMatch(conflict -> "AGENTS.md".equals(conflict.path())));
        assertTrue(Files.readString(project.resolve(".camel-kit/conflicts/AGENTS.md")).contains("camel-start"));
    }

    @Test
    void replaceOverwritesOnlyTheNamedFile() throws Exception {
        Path project = existingProject(tempDir.resolve("orders"));

        InitResult result = new InitService().initialize(
                request(project, "claude", List.of("AGENTS.md", "docs/constitution.md")));

        String agentsMd = Files.readString(project.resolve("AGENTS.md"));
        assertTrue(agentsMd.startsWith(ManagedFiles.BLOCK_START), agentsMd);
        assertFalse(agentsMd.contains("Team rules"));
        assertFalse(Files.readString(project.resolve("docs/constitution.md")).contains("Our own constitution"));
        assertEquals(EXISTING_PROJECT.get(".claude/settings.json"),
                Files.readString(project.resolve(".claude/settings.json")));
        assertEquals(EXISTING_PROJECT.get("mvnw"), Files.readString(project.resolve("mvnw")));
        assertFalse(result.conflicts().stream().anyMatch(conflict -> "docs/constitution.md".equals(conflict.path())));
        assertFalse(Files.exists(project.resolve(".camel-kit/conflicts/docs/constitution.md")));

        // Once replaced, the file is Camel-Kit's and later runs keep it current without asking again.
        InitResult again = new InitService().initialize(request(project, "claude"));
        assertFalse(again.conflicts().stream().anyMatch(conflict -> "docs/constitution.md".equals(conflict.path())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", "src/main/resources/camel/orders.camel.yaml", "../outside.md", "README.md"})
    void replaceRejectsFilesInitDoesNotOwnBeforeAnyProjectWrites(String path) throws Exception {
        Path project = existingProject(tempDir.resolve("orders"));
        List<String> before = snapshot(project);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new InitService().initialize(request(project, "claude", List.of(path))));

        assertTrue(error.getMessage().contains(path), error.getMessage());
        assertEquals(before, snapshot(project));
    }

    @Test
    void replaceOfMissingFileIsReportedInsteadOfSilentlyIgnored() throws Exception {
        Path project = tempDir.resolve("orders");

        InitResult result = new InitService().initialize(request(project, "claude", List.of("QWEN.md")));

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.message().contains("--replace QWEN.md")),
                result.warnings().toString());
    }

    @Test
    void missingLedgerNeverImpliesThatCamelKitOwnsExistingFiles() throws Exception {
        Path project = tempDir.resolve("orders");
        new InitService().initialize(request(project, "claude"));
        // A workspace initialized before the ledger existed: configuration present, no record of what was written.
        Files.delete(project.resolve(ManagedFiles.LEDGER));
        Files.writeString(project.resolve("AGENTS.md"), "# Team rules\n\nNever deploy on Fridays.\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n# team wrapper\n");
        Files.writeString(project.resolve(".claude/settings.json"), "{\"permissions\":{\"deny\":[\"Bash(rm *)\"]}}\n");

        InitResult result = new InitService().initialize(request(project, "claude"));

        String agentsMd = Files.readString(project.resolve("AGENTS.md"));
        assertTrue(agentsMd.startsWith("# Team rules\n\nNever deploy on Fridays.\n\n" + ManagedFiles.BLOCK_START),
                agentsMd);
        assertEquals("#!/bin/sh\n# team wrapper\n", Files.readString(project.resolve("mvnw")));
        assertEquals("{\"permissions\":{\"deny\":[\"Bash(rm *)\"]}}\n",
                Files.readString(project.resolve(".claude/settings.json")));
        List<String> conflicts = result.conflicts().stream().map(ManagedFiles.Change::path).toList();
        assertTrue(conflicts.containsAll(List.of("mvnw", ".claude/settings.json")), conflicts.toString());

        // Only an explicit selection replaces them.
        new InitService().initialize(request(project, "claude", List.of("mvnw", ".claude/settings.json")));
        assertFalse(Files.readString(project.resolve("mvnw")).contains("team wrapper"));
        assertTrue(Files.readString(project.resolve(".claude/settings.json")).contains("allow"));
    }

    @Test
    void interruptedFirstRunDoesNotTurnUserFilesIntoCamelKitFiles() throws Exception {
        Path project = existingProject(tempDir.resolve("orders"));
        // State left behind when the first run stopped right after writing its configuration.
        Files.createDirectories(project.resolve(".camel-kit"));
        Files.writeString(project.resolve(".camel-kit/config.properties"), "project.name=orders\n");
        Files.writeString(project.resolve(ManagedFiles.LEDGER), "");

        new InitService().initialize(request(project, "claude"));

        assertTrue(Files.readString(project.resolve("AGENTS.md")).startsWith(EXISTING_PROJECT.get("AGENTS.md")));
        assertEquals(EXISTING_PROJECT.get("mvnw"), Files.readString(project.resolve("mvnw")));
    }

    @Test
    void userFilesNextToShippedSkillsSurviveWhileShippedSkillsAreRefreshed() throws Exception {
        Path project = tempDir.resolve("orders");
        new InitService().initialize(request(project, "claude"));
        Path skills = project.resolve(".claude/skills");
        Path shipped = skills.resolve("camel-start/SKILL.md");
        String generated = Files.readString(shipped);
        Files.writeString(shipped, "edited shipped skill\n");
        Files.writeString(skills.resolve("camel-start/team-notes.md"), "our notes\n");
        Files.createDirectories(skills.resolve("deploy-orders"));
        Files.writeString(skills.resolve("deploy-orders/SKILL.md"), "our own skill\n");

        new InitService().initialize(request(project, "claude"));

        assertEquals(generated, Files.readString(shipped));
        assertEquals("our notes\n", Files.readString(skills.resolve("camel-start/team-notes.md")));
        assertEquals("our own skill\n", Files.readString(skills.resolve("deploy-orders/SKILL.md")));
    }

    @Test
    void selectedWrapperFileIsReplacedWhileTheOthersArePreserved() throws Exception {
        Path project = existingProject(tempDir.resolve("orders"));
        Map<String, Action> plan = new InitService().preview(request(project, "claude", List.of("mvnw"))).stream()
                .collect(Collectors.toMap(ManagedFiles.Change::path, ManagedFiles.Change::action));

        InitResult result = new InitService().initialize(request(project, "claude", List.of("mvnw")));

        assertEquals(Action.REFRESH, plan.get("mvnw"));
        assertEquals(Action.PRESERVE, plan.get("mvnw.cmd"));
        assertEquals(Action.PRESERVE, plan.get(".mvn/wrapper/maven-wrapper.properties"));
        assertNotEquals(EXISTING_PROJECT.get("mvnw"), Files.readString(project.resolve("mvnw")));
        assertTrue(Files.isExecutable(project.resolve("mvnw")));
        assertEquals(EXISTING_PROJECT.get("mvnw.cmd"), Files.readString(project.resolve("mvnw.cmd")));
        assertEquals(EXISTING_PROJECT.get(".mvn/wrapper/maven-wrapper.properties"),
                Files.readString(project.resolve(".mvn/wrapper/maven-wrapper.properties")));
        List<String> conflicts = result.conflicts().stream().map(ManagedFiles.Change::path).toList();
        assertFalse(conflicts.contains("mvnw"), conflicts.toString());
        assertTrue(conflicts.containsAll(List.of("mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")));
        assertFalse(result.warnings().stream().anyMatch(warning -> warning.message().contains("--replace")),
                result.warnings().toString());
    }

    @Test
    void wrapperFileIsNotAddedToAProjectOwnedWrapperAndTheSkippedReplacementIsReported() throws Exception {
        Path project = existingProject(tempDir.resolve("orders"));
        Files.delete(project.resolve("mvnw.cmd"));
        Map<String, Action> plan = new InitService().preview(request(project, "claude", List.of("mvnw.cmd"))).stream()
                .collect(Collectors.toMap(ManagedFiles.Change::path, ManagedFiles.Change::action));

        InitResult result = new InitService().initialize(request(project, "claude", List.of("mvnw.cmd")));

        assertFalse(plan.containsKey("mvnw.cmd"), plan.toString());
        assertFalse(Files.exists(project.resolve("mvnw.cmd")));
        assertEquals(EXISTING_PROJECT.get("mvnw"), Files.readString(project.resolve("mvnw")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.message().contains("--replace mvnw.cmd")),
                result.warnings().toString());
    }

    @Test
    void rejectedPreviewLeavesTheProjectUnchanged() throws Exception {
        Path project = tempDir.resolve("orders");
        new InitService().initialize(request(project, "opencode"));
        assertTrue(Files.isRegularFile(project.resolve(".opencode/agents/camel-ship-worker.md")));
        Files.writeString(project.resolve("opencode.json"), "{ not json");
        List<String> before = snapshot(project);

        assertThrows(Exception.class, () -> new InitService().preview(request(project, "opencode")));

        assertEquals(before, snapshot(project));
    }

    @Test
    void previewReportsEachSharedFileWithoutWriting() throws Exception {
        Path project = existingProject(tempDir.resolve("orders"));
        Files.delete(project.resolve("docs/constitution.md"));
        List<String> before = snapshot(project);

        Map<String, Action> plan = new InitService().preview(request(project, "claude", List.of("CLAUDE.md"))).stream()
                .collect(Collectors.toMap(ManagedFiles.Change::path, ManagedFiles.Change::action));

        assertEquals(before, snapshot(project));
        assertEquals(Action.MERGE, plan.get("AGENTS.md"));
        assertEquals(Action.REFRESH, plan.get("CLAUDE.md"));
        assertEquals(Action.PRESERVE, plan.get(".claude/settings.json"));
        assertEquals(Action.CREATE, plan.get("docs/constitution.md"));
        assertEquals(Action.PRESERVE, plan.get("mvnw"));
        assertEquals(Action.CREATE, plan.get(".mcp.json"));
        assertFalse(plan.containsKey("QWEN.md"), "files of other agents are not written");
    }

    @Test
    void previewMatchesWhatInitializationDoes() throws Exception {
        Path project = existingProject(tempDir.resolve("orders"));
        Map<String, Action> plan = new InitService().preview(request(project, "claude")).stream()
                .collect(Collectors.toMap(ManagedFiles.Change::path, ManagedFiles.Change::action));

        List<String> preserved = new InitService().initialize(request(project, "claude")).conflicts().stream()
                .map(ManagedFiles.Change::path).sorted().toList();

        assertEquals(preserved, plan.entrySet().stream().filter(entry -> entry.getValue() == Action.PRESERVE)
                .map(Map.Entry::getKey).sorted().toList());
    }

    @Test
    void wrapperReplacementFlagAndSelectedFilesShareThePreviewPlan() throws Exception {
        Path project = existingProject(tempDir.resolve("wrapper-options"));
        InitRequest request = new InitRequest(
                "orders", "claude", project, "default", true, false, null,
                "camel-kit", "5.0.1", CamelKitMain.distribution(), Printer.noop(), InitProgress.noop(),
                InitReporter.noop(), false, true, false, List.of("CLAUDE.md"));
        List<String> before = snapshot(project);
        Map<String, Action> plan = new InitService().preview(request).stream()
                .collect(Collectors.toMap(ManagedFiles.Change::path, ManagedFiles.Change::action));
        assertEquals(before, snapshot(project));
        for (String path : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties", "CLAUDE.md")) {
            assertEquals(Action.REFRESH, plan.get(path), path);
        }
        assertEquals(Action.PRESERVE, plan.get(".claude/settings.json"));

        new InitService().initialize(request);

        for (String path : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties", "CLAUDE.md")) {
            assertNotEquals(EXISTING_PROJECT.get(path), Files.readString(project.resolve(path)), path);
        }
        assertEquals(EXISTING_PROJECT.get(".claude/settings.json"),
                Files.readString(project.resolve(".claude/settings.json")));
    }

    @Test
    void serviceDryRunValidatesWithoutRunningBaselineOrInvalidatingWorker() throws Exception {
        Path project = tempDir.resolve("service-preview");
        Files.createDirectories(project.resolve(".opencode/agents"));
        Files.writeString(project.resolve("opencode.json"), "not JSON");
        Files.writeString(project.resolve(".opencode/agents/camel-ship-worker.md"), "existing worker");
        List<String> before = snapshot(project);
        InitRequest request = new InitRequest(
                "orders", "opencode", project, "default", true, false, null,
                "camel-kit", "5.0.1", CamelKitMain.distribution(), Printer.noop(), InitProgress.noop(),
                InitReporter.noop(), true, false, true, List.of());

        assertThrows(InvalidAgentConfigurationException.class, () -> new InitService().initialize(request));
        assertEquals(before, snapshot(project));
    }

    private static Map<String, String> existingProject() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml",
                "<project><modelVersion>4.0.0</modelVersion></project>\n");
        files.put("src/main/resources/camel/orders.camel.yaml", "- route:\n    from:\n      uri: direct:orders\n");
        files.put("src/main/resources/application.properties", "camel.main.name=orders\n");
        files.put("mvnw", "#!/bin/sh\n# project wrapper\n");
        files.put("mvnw.cmd", "@REM project wrapper\r\n");
        files.put(".mvn/wrapper/maven-wrapper.properties", "distributionUrl=https://example.invalid/maven-3.8.8.zip\n");
        files.put("docs/constitution.md", "# Our own constitution\n");
        files.put("AGENTS.md", "# Team rules\n\nAsk before touching billing routes.\n");
        files.put("CLAUDE.md", "# Claude notes\n");
        files.put("QWEN.md", "# Qwen notes\n");
        files.put(".qwenignore", "secrets/\n");
        files.put(".claude/settings.json", "{\"permissions\":{\"deny\":[\"Bash(rm *)\"]}}\n");
        files.put(".github/copilot-instructions.md", "# Copilot notes\n");
        files.put(".bob/custom_modes.yaml", "customModes: []\n");
        return files;
    }

    private static Path existingProject(Path project) throws Exception {
        for (Map.Entry<String, String> file : EXISTING_PROJECT.entrySet()) {
            Path target = project.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return project;
    }

    private static Map<String, String> sharedFiles(Path project) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : EXISTING_PROJECT.keySet()) {
            files.put(path, Files.readString(project.resolve(path)));
        }
        return files;
    }

    private static int occurrences(String text, String part) {
        return (text.length() - text.replace(part, "").length()) / part.length();
    }

    private static Properties ledger(Path project) throws Exception {
        Properties ledger = new Properties();
        try (var in = Files.newInputStream(project.resolve(ManagedFiles.LEDGER))) {
            ledger.load(in);
        }
        return ledger;
    }

    private static List<String> snapshot(Path root) throws Exception {
        List<String> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                entries.add(Files.isDirectory(path)
                        ? "directory:" + root.relativize(path)
                        : "file:" + root.relativize(path) + "=" + Files.readString(path));
            }
        }
        return entries;
    }

    private static InitRequest request(Path targetDir, String agentName) {
        return request(targetDir, agentName, List.of());
    }

    private static InitRequest request(Path targetDir, String agentName, List<String> replace) {
        return new InitRequest(
                "orders", agentName, targetDir, "default", true, false, null, "camel-kit", "5.0.1",
                CamelKitMain.distribution(), Printer.noop(), InitProgress.noop(), InitReporter.noop(), replace);
    }
}
