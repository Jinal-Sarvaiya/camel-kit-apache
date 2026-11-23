package org.apache.camel.kit.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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

    @Test
    void obstructedCatalogProposalIsReportedBeforeAnyWrites() throws Exception {
        Path project = tempDir.resolve("orders");
        var service = new InitService();
        var init = request(project, "claude");
        service.initialize(init);
        String path = ".claude/skills/camel-validate/SKILL.md";
        Files.writeString(project.resolve(path), "My validation rules");
        Path proposal = project.resolve(ManagedFiles.PROPOSALS + "/catalog/" + path.substring(1) + ".proposed");
        Files.createDirectories(proposal.getParent().getParent());
        Files.writeString(proposal.getParent(), "Previous proposal at this path");
        var before = snapshot(project);
        for (var failure : List.of(
                assertThrows(InvalidAgentConfigurationException.class, () -> service.preview(init)),
                assertThrows(InvalidAgentConfigurationException.class, () -> service.initialize(init)))) {
            assertTrue(failure.getMessage().contains(proposal.getParent().toString()), failure::getMessage);
        }
        assertEquals(before, snapshot(project));
        Files.move(proposal.getParent(), project.resolve("previous-proposal"));
        service.initialize(init);
        assertEquals("My validation rules", Files.readString(project.resolve(path)));
        assertTrue(Files.isRegularFile(proposal));
    }

    @Test
    @Tag("cli-compat")
    void releasedClaudeWorkspaceUpdatesStockAndKeepsEdits() throws Exception {
        var service = new InitService();
        var init = request(tempDir, "claude");
        Files.createDirectories(tempDir.resolve(".camel-kit"));
        Files.writeString(tempDir.resolve(".camel-kit/config.properties"), "agent.name=claude");
        String guide = ".claude/skills/camel-implement/guides/run-script.md";
        String retired = ".claude/skills/camel-ship/guides/auto-fix-loop.md";
        // Exact installed 0.4.1 guide and the Ship guide from the last revision that shipped it.
        copyReleasedFixture(tempDir.resolve(guide));
        copyReleasedFixture(tempDir.resolve(retired));
        String release = Files.readString(tempDir.resolve(guide));
        String stub = ".claude/commands/camel-validate.md";
        Path commands = writeReleasedClaudeStub(tempDir).getParent();
        Files.writeString(commands.resolve("camel-start.md"), "My launch instructions");
        Files.writeString(commands.resolve("team.md"), "Team command");
        var original = snapshot(tempDir);
        var preview = service.preview(init).stream()
                .collect(Collectors.toMap(ManagedFiles.Change::path, ManagedFiles.Change::action));
        assertEquals(original, snapshot(tempDir));
        assertEquals(Action.REFRESH, preview.get(guide));
        assertEquals(Action.REMOVE, preview.get(retired));
        assertEquals(Action.REMOVE, preview.get(stub));
        assertEquals(Action.PRESERVE, preview.get(".claude/commands/camel-start.md"));
        service.initialize(init);
        assertFalse(Files.exists(tempDir.resolve(retired)));
        assertFalse(Files.exists(tempDir.resolve(stub)));
        assertEquals("My launch instructions", Files.readString(commands.resolve("camel-start.md")));
        assertEquals("Team command", Files.readString(commands.resolve("team.md")));
        assertNotEquals(release, Files.readString(tempDir.resolve(guide)));
        assertTrue(ledger(tempDir).containsKey("catalog/" + guide));

        // Reverting a tracked file to an old stock version is a local edit.
        Files.writeString(tempDir.resolve(guide), release);
        assertTrue(service.initialize(init).conflicts().stream().anyMatch(c -> c.path().equals(guide)));
        assertEquals(release, Files.readString(tempDir.resolve(guide)));
    }

    @ParameterizedTest
    @CsvSource({"claude, codex", "codex, claude"})
    @Tag("cli-compat")
    void releasedWorkspaceUpgradesBothAssistantsInEitherOrder(String first, String second) throws Exception {
        Path project = tempDir.resolve("released");
        Path fresh = tempDir.resolve("fresh");
        Files.createDirectories(project.resolve(".camel-kit"));
        Files.writeString(project.resolve(".camel-kit/config.properties"), "agent.name=codex\n");
        Path commands = writeReleasedClaudeStub(project).getParent();
        var service = new InitService();
        for (String host : List.of(first, second)) {
            String skills = AgentRegistry.get(host).skillsDirectory();
            copyReleasedFixture(project.resolve(skills).resolve("camel-implement/guides/run-script.md"));
            service.initialize(request(fresh, host));
        }
        for (String host : List.of(first, second)) {
            var init = request(project, host);
            String guide = AgentRegistry.get(host).skillsDirectory() + "/camel-implement/guides/run-script.md";
            assertTrue(service.preview(init).stream()
                    .anyMatch(c -> c.path().equals(guide) && c.action() == Action.REFRESH));
            service.initialize(init);
        }
        var expected = catalogRecords(fresh);
        assertEquals(expected, catalogRecords(project));
        for (String path : expected.keySet()) {
            assertEquals(-1L, Files.mismatch(fresh.resolve(path), project.resolve(path)), path);
        }
        assertFalse(Files.exists(commands));
        assertFalse(Files.exists(project.resolve(ManagedFiles.PROPOSALS + "/catalog")));
    }

    @Test
    @Tag("cli-compat")
    void releasedWorkspaceUpgradesWithLatin1Properties() throws Exception {
        Path config = Files.createDirectories(tempDir.resolve(".camel-kit")).resolve("config.properties");
        Files.writeString(config, "agent.name=claude\nteam.owner=André\n", StandardCharsets.ISO_8859_1);
        Files.writeString(config.resolveSibling("config.yaml"), "old: [");
        Path stub = writeReleasedClaudeStub(tempDir);
        var init = request(tempDir, "claude");
        var service = new InitService();

        assertTrue(service.preview(init).stream().anyMatch(c -> c.action() == Action.REMOVE
                && c.path().equals(".claude/commands/camel-validate.md")));
        service.initialize(init);
        assertFalse(Files.exists(stub));
        var settings = new Properties();
        try (var in = Files.newInputStream(config)) {
            settings.load(in);
        }
        assertEquals("André", settings.getProperty("team.owner"));
    }

    @ParameterizedTest
    @CsvSource({"config.properties, false", "config.yaml, false", "config.properties, true", "config.yaml, true"})
    @Tag("cli-compat")
    void malformedConfigLeavesWorkspaceUntouchedAndRepairCanBeRetried(String filename, boolean trackedFromInit)
            throws Exception {
        Path config = Files.createDirectories(tempDir.resolve(".camel-kit")).resolve(filename);
        boolean properties = filename.equals("config.properties");
        Files.writeString(config,
                properties ? "agent.name=claude\nextra=\\u12x4\n" : "agent:\n  name: claude\nextra: [");
        if (trackedFromInit) {
            Files.writeString(tempDir.resolve(ManagedFiles.LEDGER), "catalog.tracked-from-init=true\n");
        }
        Path stub = writeReleasedClaudeStub(tempDir);
        var service = new InitService();
        var init = request(tempDir, "claude");
        var original = snapshot(tempDir);
        var preview = assertThrows(InvalidAgentConfigurationException.class, () -> service.preview(init));
        assertTrue(preview.getMessage().contains(config.toString()), preview::getMessage);
        assertEquals(original, snapshot(tempDir));
        var apply = assertThrows(InvalidAgentConfigurationException.class, () -> service.initialize(init));
        assertEquals(preview.getMessage(), apply.getMessage());
        assertEquals(original, snapshot(tempDir));

        Files.writeString(config, properties ? "agent.name=claude\n" : "agent:\n  name: claude\n");
        service.initialize(init);
        assertEquals(trackedFromInit, Files.exists(stub));
        assertEquals(trackedFromInit, Boolean.parseBoolean(ledger(tempDir).getProperty("catalog.tracked-from-init")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "agent.name=   \n")
    void adoptingAnExistingProjectPreservesCopiesOfReleasedFilesAcrossRetries(String configuration) throws Exception {
        if (configuration != null) {
            Path config = Files.createDirectories(tempDir.resolve(".camel-kit")).resolve("config.properties");
            Files.writeString(config, configuration);
        }
        String path = ".claude/skills/camel-implement/guides/run-script.md";
        Path guide = tempDir.resolve(path);
        copyReleasedFixture(guide);
        Path stub = writeReleasedClaudeStub(tempDir);
        String originalGuide = Files.readString(guide);
        String originalStub = Files.readString(stub);
        var service = new InitService();
        service.preview(request(tempDir, "claude"));
        assertFalse(Files.exists(tempDir.resolve(ManagedFiles.LEDGER)));
        for (int run = 0; run < 2; run++) {
            service.initialize(request(tempDir, "claude"));
            assertEquals(originalGuide, Files.readString(guide));
            assertEquals(originalStub, Files.readString(stub));
        }
        service.initialize(request(tempDir, "claude", List.of(path)));
        assertNotEquals(originalGuide, Files.readString(guide));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("cli-compat")
    void historicalSkillNameAloneDoesNotMakeAPersonalSkillRetired(boolean existingKitWorkspace) throws Exception {
        if (existingKitWorkspace) {
            Files.createDirectories(tempDir.resolve(".camel-kit"));
            Files.writeString(tempDir.resolve(".camel-kit/config.properties"), "agent.name=claude\n");
        }
        String path = ".claude/skills/camel-flow/SKILL.md";
        Path skill = tempDir.resolve(path);
        Files.createDirectories(skill.getParent());
        String content = "---\nname: camel-flow\ndescription: Team inventory checklist\n---\nRun the team checklist.\n";
        Files.writeString(skill, content);
        var init = request(tempDir, "claude");
        var service = new InitService();
        service.initialize(init);
        var doctor = new DoctorService().inspect(new DoctorRequest(tempDir));
        assertFalse(doctor.hasFailures(), doctor.findings().toString());
        assertTrue(service.preview(init).stream().noneMatch(c -> c.path().equals(path)));
        assertTrue(service.initialize(init).conflicts().stream().noneMatch(c -> c.path().equals(path)));
        assertEquals(content, Files.readString(skill));
        assertFalse(ledger(tempDir).containsKey("catalog/" + path));
    }

    private static void copyReleasedFixture(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        try (var in = InitSafeAdoptionTest.class.getResourceAsStream("/pre-ledger/" + file.getFileName())) {
            Files.copy(in, file);
        }
    }

    private static Path writeReleasedClaudeStub(Path project) throws Exception {
        Path commands = Files.createDirectories(project.resolve(".claude/commands"));
        return Files.writeString(commands.resolve("camel-validate.md"),
                "Read .claude/skills/camel-validate/SKILL.md and follow those instructions. Requested input: $ARGUMENTS");
    }

    private static Map<String, String> catalogRecords(Path project) throws Exception {
        var records = ledger(project);
        return records.stringPropertyNames().stream().filter(key -> key.startsWith("catalog/"))
                .collect(Collectors.toMap(key -> key.substring("catalog/".length()), records::getProperty));
    }

    @ParameterizedTest
    @MethodSource("agents")
    @Tag("cli-compat")
    void missingAndStaleCatalogRecordsConvergeWithoutRewritingTheFiles(String host) throws Exception {
        var service = new InitService();
        var init = request(tempDir, host);
        service.initialize(init);
        Path receipt = tempDir.resolve(ManagedFiles.LEDGER);
        String skill = AgentRegistry.get(host).skillsDirectory() + "/camel-validate/SKILL.md";
        Path file = tempDir.resolve(skill);
        Files.writeString(file, Files.readString(file).replace("\r\n", "\n").replace("\n", "\r\n"));
        var modified = Files.getLastModifiedTime(file);
        Properties initial = ledger(tempDir);
        Files.delete(receipt);
        for (int pass = 0; pass < 3; pass++) {
            if (pass == 1) {
                var previous = ledger(tempDir);
                previous.setProperty("catalog/" + skill, "stale");
                try (var out = Files.newBufferedWriter(receipt)) {
                    previous.store(out, null);
                }
            }
            var preview = service.preview(init);
            assertEquals(initial.stringPropertyNames().stream().filter(key -> key.startsWith("catalog/")).count(),
                    preview.stream().filter(c -> c.action() == Action.UNCHANGED).count());
            assertEquals(Set.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"),
                    service.initialize(init).conflicts().stream().map(ManagedFiles.Change::path)
                            .collect(Collectors.toSet()));
            assertEquals(initial.getProperty("catalog/" + skill), ledger(tempDir).getProperty("catalog/" + skill));
            assertEquals(modified, Files.getLastModifiedTime(file));
            assertFalse(Files.exists(tempDir.resolve(ManagedFiles.PROPOSALS)));
        }
    }

    @Test
    @Tag("cli-compat")
    void directoryReplacementAcceptsOnlySelectedAssetsAndArchivesPreviousProposals() throws Exception {
        var service = new InitService();
        service.initialize(request(tempDir, "claude"));
        Path skills = tempDir.resolve(".claude/skills");
        String selected = ".claude/skills/camel-validate/SKILL.md";
        String sibling = ".claude/skills/camel-start/SKILL.md";
        String original = Files.readString(tempDir.resolve(selected));
        Files.writeString(tempDir.resolve(selected), "Validation edits");
        Files.writeString(tempDir.resolve(sibling), "Start edits");
        Path personal = skills.resolve("camel-validate/personal.md");
        Files.writeString(personal, "Team rules");
        Path old = tempDir.resolve(".camel-kit/conflicts").resolve(selected);
        Files.createDirectories(old.getParent());
        Files.writeString(old, "Previous merge notes");
        var request = request(tempDir, "claude", List.of(".claude/skills/camel-validate"));
        var before = snapshot(tempDir);
        var preview = service.preview(request);
        assertEquals(before, snapshot(tempDir));
        assertTrue(preview.stream().anyMatch(c -> c.action() == Action.MOVE));
        var result = service.initialize(request);
        assertEquals(List.of(sibling), result.conflicts().stream().map(ManagedFiles.Change::path).toList());
        assertEquals(original, Files.readString(tempDir.resolve(selected)));
        assertEquals("Previous merge notes", Files.readString(tempDir.resolve(
                ".camel-kit/conflicts/previous-claude-skills/camel-validate/SKILL.md")));
        assertFalse(Files.exists(old));
        var proposal = result.conflicts().getFirst().proposal();
        assertEquals(tempDir.resolve(".camel-kit/conflicts/catalog/claude/skills/camel-start/SKILL.md.proposed"),
                proposal);
        assertTrue(Files.isRegularFile(proposal));
        assertTrue(service.initialize(request(tempDir, "claude", List.of(".claude/skills"))).conflicts().isEmpty());
        assertFalse(Files.exists(proposal));
        assertEquals("Team rules", Files.readString(personal));
    }

    @ParameterizedTest
    @MethodSource("agents")
    @Tag("cli-compat")
    void selectedCatalogFilesRequireOwnershipOrExplicitReplacement(String agentName) throws Exception {
        Path project = tempDir.resolve(agentName);
        var agent = AgentRegistry.get(agentName);
        String skill = agent.skillsDirectory() + "/camel-validate/SKILL.md";
        String guide = agent.skillsDirectory() + "/shared/iron-laws.md";
        var paths = new ArrayList<>(List.of(skill, guide));
        if (agent.generatesCommandStubs()) {
            paths.add(agent.commandDirectory() + "/camel-validate." + agent.fileFormat());
        }
        var service = new InitService();
        service.initialize(request(project, agentName));
        var generated = new HashMap<String, String>();
        for (String path : paths) {
            generated.put(path, Files.readString(project.resolve(path)));
            Files.writeString(project.resolve(path), "My " + path);
        }
        // This guide predates catalog tracking, so even subsequent runs must not claim it.
        Properties original = ledger(project);
        original.remove("catalog/" + guide);
        try (var out = Files.newBufferedWriter(project.resolve(ManagedFiles.LEDGER))) {
            original.store(out, null);
        }
        var before = snapshot(project);
        var preview = service.preview(request(project, agentName));
        assertEquals(before, snapshot(project));
        for (String path : paths) {
            assertTrue(preview.stream().anyMatch(c -> c.path().equals(path) && c.action() == Action.PRESERVE), path);
        }

        for (int run = 0; run < 2; run++) {
            var result = service.initialize(request(project, agentName));
            assertEquals(Set.copyOf(paths), result.conflicts().stream().map(ManagedFiles.Change::path)
                    .collect(Collectors.toSet()));
            for (String path : paths) {
                assertEquals("My " + path, Files.readString(project.resolve(path)));
                assertEquals(generated.get(path),
                        Files.readString(project
                                .resolve(ManagedFiles.PROPOSALS + "/catalog/" + path.substring(1) + ".proposed")));
                assertEquals(original.getProperty("catalog/" + path), ledger(project).getProperty("catalog/" + path));
            }
        }
        var replacement = request(project, agentName, List.of(skill));
        assertTrue(service.preview(replacement).stream()
                .anyMatch(c -> c.path().equals(skill) && c.action() == Action.REFRESH));
        assertTrue(
                service.initialize(replacement).warnings().stream().noneMatch(w -> w.message().contains("--replace")));
        assertEquals(generated.get(skill), Files.readString(project.resolve(skill)));
        assertFalse(
                Files.exists(project.resolve(ManagedFiles.PROPOSALS + "/catalog/" + skill.substring(1) + ".proposed")));
        assertEquals("My " + guide, Files.readString(project.resolve(guide)));
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

    static List<String> unmergeableCodexConfigs() {
        return List.of(
                "model = [\n",
                "[mcp_servers.camel]\ncommand = \"custom\"\n",
                "# BEGIN CAMEL-KIT MANAGED MCP SERVERS\nmodel = \"x\"\n",
                // A valid block does not excuse a managed server defined outside it.
                "[mcp_servers.camel]\ncommand = \"custom\"\n\n# BEGIN CAMEL-KIT MANAGED MCP SERVERS\n"
                                                                          + "[mcp_servers.camel-knowledge]\ncommand = \"old-knowledge\"\n"
                                                                          + "# END CAMEL-KIT MANAGED MCP SERVERS\n",
                // Valid on their own, but the managed tables cannot be added to them.
                "mcp_servers = {}\n",
                "mcp_servers = \"none\"\n");
    }

    @ParameterizedTest
    @MethodSource("unmergeableCodexConfigs")
    void unmergeableCodexConfigIsRejectedByPreviewAndInitBeforeAnyProjectWrites(String content) throws Exception {
        Path project = tempDir.resolve("orders");
        Path config = Files.createDirectories(project.resolve(".codex")).resolve("config.toml");
        Files.writeString(config, content);
        List<String> before = snapshot(project);

        InvalidAgentConfigurationException previewError = assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService().preview(request(project, "codex")));
        assertEquals(before, snapshot(project));

        InvalidAgentConfigurationException initError = assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService().initialize(request(project, "codex")));
        assertEquals(before, snapshot(project));
        assertEquals(previewError.getMessage(), initError.getMessage());
        assertTrue(initError.getMessage().contains(".codex/config.toml"), initError.getMessage());
    }

    @Test
    void codexConfigThatIsNotARegularFileIsRejectedByPreviewAndInitBeforeAnyProjectWrites() throws Exception {
        Path project = tempDir.resolve("orders");
        Files.createDirectories(project.resolve(".codex/config.toml"));
        List<String> before = snapshot(project);

        InvalidAgentConfigurationException previewError = assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService().preview(request(project, "codex")));
        InvalidAgentConfigurationException initError = assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService().initialize(request(project, "codex")));

        assertEquals(before, snapshot(project));
        assertEquals(previewError.getMessage(), initError.getMessage());
        assertTrue(initError.getMessage().contains("must be a regular file"), initError.getMessage());
    }

    @Test
    void reinitializationOfCodexReplacesTheManagedBlockAndPassesPreview() throws Exception {
        Path project = tempDir.resolve("orders");
        new InitService().initialize(request(project, "codex"));
        Path config = project.resolve(".codex/config.toml");
        String first = Files.readString(config);

        new InitService().preview(request(project, "codex"));
        new InitService().initialize(request(project, "codex"));

        assertEquals(first, Files.readString(config));
    }

    @Test
    void validCodexConfigIsAcceptedByPreviewAndMergedByInit() throws Exception {
        Path project = tempDir.resolve("orders");
        Path config = Files.createDirectories(project.resolve(".codex")).resolve("config.toml");
        String existing = "model_reasoning_effort = \"high\"\n\n[mcp_servers.other]\ncommand = \"other-mcp\"\n";
        Files.writeString(config, existing);
        List<String> before = snapshot(project);

        List<ManagedFiles.Change> preview = new InitService().preview(request(project, "codex"));

        assertEquals(before, snapshot(project));
        assertTrue(preview.stream().anyMatch(
                change -> change.path().equals(".codex/config.toml") && change.action() == Action.MERGE),
                preview.toString());

        new InitService().initialize(request(project, "codex"));

        String merged = Files.readString(config);
        assertTrue(merged.startsWith(existing), merged);
        assertTrue(merged.contains("[mcp_servers.camel]"), merged);
    }

    @Test
    void preservedWrapperIsReportedWithoutAProposedCopy() throws Exception {
        Path project = tempDir.resolve("orders");
        Files.createDirectories(project);
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n# team wrapper\n");

        InitResult result = new InitService().initialize(request(project, "claude"));

        assertEquals("#!/bin/sh\n# team wrapper\n", Files.readString(project.resolve("mvnw")));
        ManagedFiles.Change conflict = result.conflicts().stream()
                .filter(change -> change.path().equals("mvnw")).findFirst().orElseThrow();
        assertNull(conflict.proposal());
        assertFalse(Files.exists(project.resolve(ManagedFiles.PROPOSALS).resolve("mvnw")));
        // No partial wrapper is assembled around the project's own file, in the project or as a proposal.
        for (String other : List.of("mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            assertFalse(Files.exists(project.resolve(other)), other);
            assertFalse(Files.exists(project.resolve(ManagedFiles.PROPOSALS).resolve(other)), other);
        }
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
    @ValueSource(strings = {
            "pom.xml", "src/main/resources/camel/orders.camel.yaml", "../outside.md", "README.md", ".", ".claude",
            ".bob/skills", ".claude/skills/personal"})
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
    void editedSkillsAndNeighboringProjectFilesSurviveReinitialization() throws Exception {
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

        assertEquals("edited shipped skill\n", Files.readString(shipped));
        assertEquals(generated,
                Files.readString(
                        project.resolve(".camel-kit/conflicts/catalog/claude/skills/camel-start/SKILL.md.proposed")));
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
