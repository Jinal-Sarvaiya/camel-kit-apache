package org.apache.camel.kit.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.generator.InvalidAgentConfigurationException;
import org.apache.camel.kit.output.Printer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class InitServiceTest {

    private static final String EXPECTED_CITRUS_MCP_VERSION = "5.0.1";

    @TempDir
    Path tempDir;

    @Test
    void initializesWorkspaceAndReturnsStructuredResult() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        RecordingReporter reporter = new RecordingReporter();
        Path targetDir = tempDir.resolve("orders");

        InitResult result = new InitService().initialize(request(targetDir, "bob2", progress, reporter));

        assertEquals("orders", result.projectName());
        assertEquals("bob2", result.agentName());
        assertEquals(targetDir, result.targetDir());
        assertEquals("5.0.1", result.citrusVersion());
        assertEquals(0, result.citrusSchemaCount());
        assertEquals("3.9.9", result.mavenWrapperVersion());
        assertFalse(result.createdPaths().isEmpty());

        assertTrue(Files.isRegularFile(targetDir.resolve(".camel-kit/config.properties")));
        assertTrue(Files.isRegularFile(targetDir.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".bob/mcp.json")));
        assertTrue(Files.isRegularFile(targetDir.resolve("mvnw")));

        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("project.name=orders"));
        assertTrue(config.contains("agent.name=bob2"));
        assertTrue(config.contains("project.sourcePlatform=mulesoft"));
        assertTrue(config.contains("citrus.version=5.0.1"));
        assertTrue(config.contains("citrus.mcp.enabled=false"));
        assertFalse(config.contains("citrus.mcp.version="));

        assertTrue(progress.events().contains("start:Creating project structure"));
        assertTrue(progress.events().contains("start:Generating IBM Bob 2 workspace"));
        assertEquals("3.9.9", reporter.mavenVersion());
        assertTrue(reporter.wasGraphSkipped() || reporter.graph() != null || !reporter.warnings().isEmpty());
    }

    @Test
    void allAgentsOmitCitrusByDefaultAndPassDoctorMcpChecks() throws Exception {
        for (String agentName : AgentRegistry.names()) {
            Path targetDir = tempDir.resolve(agentName);
            new InitService().initialize(request(targetDir, agentName, InitProgress.noop(), InitReporter.noop()));

            String mcp = Files.readString(targetDir.resolve(AgentRegistry.get(agentName).mcpConfigPath()));
            assertFalse(mcp.contains("citrus-mcp-server"), agentName + ": citrus should be absent by default");
            DoctorResult result = new DoctorService().inspect(new DoctorRequest(targetDir));
            assertTrue(result.findings().stream().anyMatch(finding -> "mcp".equals(finding.category())
                    && finding.status() == DoctorFinding.Status.PASS), agentName + ": " + result.findings());
            assertFalse(result.findings().stream().anyMatch(finding -> "mcp".equals(finding.category())
                    && finding.status() == DoctorFinding.Status.FAIL), agentName + ": " + result.findings());
        }
    }

    @Test
    void allAgentsIncludeCitrusWhenOptedInAndPassDoctorMcpChecks() throws Exception {
        for (String agentName : AgentRegistry.names()) {
            Path targetDir = tempDir.resolve("citrus-" + agentName);
            new InitService().initialize(requestWithCitrusMcp(targetDir, agentName));

            String mcp = Files.readString(targetDir.resolve(AgentRegistry.get(agentName).mcpConfigPath()));
            assertTrue(mcp.contains(
                    "org.citrusframework:citrus-mcp-server:" + EXPECTED_CITRUS_MCP_VERSION + ":runner"), agentName);

            String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
            assertTrue(config.contains("citrus.mcp.enabled=true"), agentName);
            assertTrue(config.contains("citrus.mcp.version=" + EXPECTED_CITRUS_MCP_VERSION), agentName);

            DoctorResult result = new DoctorService().inspect(new DoctorRequest(targetDir));
            assertTrue(result.findings().stream().anyMatch(finding -> "mcp".equals(finding.category())
                    && finding.status() == DoctorFinding.Status.PASS), agentName + ": " + result.findings());
            assertFalse(result.findings().stream().anyMatch(finding -> "mcp".equals(finding.category())
                    && finding.status() == DoctorFinding.Status.FAIL), agentName + ": " + result.findings());
        }
    }

    @Test
    void removedTargetsFailBeforeWritingWithMigrationGuidance() {
        for (var migration : java.util.Map.of(
                "bob", "bob2", "Bob", "bob2", "BOB", "bob2",
                "gemini", "antigravity", "Gemini", "antigravity", "GEMINI", "antigravity").entrySet()) {
            Path target = tempDir.resolve(migration.getKey());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new InitService()
                            .initialize(request(target, migration.getKey(), InitProgress.noop(), InitReporter.noop())));
            assertTrue(failure.getMessage().contains("support has been removed"));
            assertTrue(failure.getMessage().contains("--ai " + migration.getValue()));
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void antigravityRejectsWrongManagedNodeTypesBeforeAnyProjectWrites() throws Exception {
        for (String name : List.of(".agents", "AGENTS.md", "GEMINI.md", ".agents/skills",
                ".agents/skills/camel-start", ".agents/skills/camel-start/SKILL.md",
                ".agents/skills/shared", ".agents/agents", ".agents/agents/camel-worker.md",
                ".agents/camel-kit-personas", ".agents/camel-kit-personas/catalog-researcher.md")) {
            Path project = Files.createTempDirectory(tempDir, "conflicting-node-");
            Files.createDirectories(project.resolve(name).getParent());
            if (!name.endsWith(".md")) {
                Files.writeString(project.resolve(name), "custom file");
            } else {
                Files.createDirectory(project.resolve(name));
            }
            List<String> before = snapshot(project);
            assertThrows(IOException.class,
                    () -> new InitService()
                            .initialize(request(project, "antigravity", InitProgress.noop(), InitReporter.noop())),
                    name);
            assertEquals(before, snapshot(project), name);
        }
    }

    @Test
    void invalidAntigravityMcpFailsBeforeAnyProjectWrites() throws Exception {
        Path config = tempDir.resolve(".agents/mcp_config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{} {}");
        List<String> before = snapshot(tempDir);
        assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService()
                        .initialize(request(tempDir, "antigravity", InitProgress.noop(), InitReporter.noop())));
        assertEquals(before, snapshot(tempDir));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mcpServers", "camel"})
    void duplicateAntigravityMcpFailsBeforeAnyProjectWrites(String key) throws Exception {
        new InitService().initialize(request(tempDir, "antigravity", InitProgress.noop(), InitReporter.noop()));
        Path config = tempDir.resolve(".agents/mcp_config.json");
        String original = Files.readString(config);
        Files.writeString(config, original.replaceFirst("\"" + key + "\"\\s*:",
                "\"" + key + "\": {}, \"" + key + "\":"));
        List<String> before = snapshot(tempDir);

        InvalidAgentConfigurationException failure = assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService()
                        .initialize(request(tempDir, "antigravity", InitProgress.noop(), InitReporter.noop())));

        assertTrue(failure.getMessage().contains("Duplicate field '" + key + "'"));
        assertEquals(before, snapshot(tempDir));
    }

    @ParameterizedTest
    @CsvSource({
            "opencode.json, mcp", "opencode.json, camel",
            "opencode.jsonc, mcp", "opencode.jsonc, camel",
            ".opencode/opencode.json, mcp", ".opencode/opencode.json, camel",
            ".opencode/opencode.jsonc, mcp", ".opencode/opencode.jsonc, camel"})
    void duplicateOpenCodeMcpFailsBeforeAnyProjectWrites(String file, String key) throws Exception {
        new InitService().initialize(request(tempDir, "opencode", InitProgress.noop(), InitReporter.noop()));
        String original = Files.readString(tempDir.resolve("opencode.json"));
        Files.writeString(tempDir.resolve(".opencode/opencode.jsonc"), original);
        Files.writeString(tempDir.resolve(file), original.replaceFirst("\"" + key + "\"\\s*:",
                "\"" + key + "\": {}, \"" + key + "\":"));
        // The only permitted change on a failed preflight is the safety cleanup of the Ship worker, whose denial
        // list can no longer be established while the project configuration is rejected.
        Path worker = tempDir.resolve(".opencode/agents/camel-ship-worker.md");
        assertTrue(Files.exists(worker), "precondition: worker generated by the first init");
        Files.delete(worker);
        List<String> before = snapshot(tempDir);
        Files.writeString(worker, "stale worker");

        InvalidAgentConfigurationException failure = assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService()
                        .initialize(request(tempDir, "opencode", InitProgress.noop(), InitReporter.noop())));

        assertTrue(failure.getMessage().contains(file));
        assertTrue(failure.getMessage().contains("Duplicate field '" + key + "'"));
        assertTrue(failure.getMessage().contains("remove duplicate keys"));
        assertFalse(Files.exists(worker), "stale Ship worker must be invalidated when preflight rejects the config");
        assertEquals(before, snapshot(tempDir));
    }

    @ParameterizedTest
    @ValueSource(strings = {"opencode.json", ".opencode/opencode.json"})
    void duplicateKeyConfigIntroducingNewServerInvalidatesShipWorkerOnReinit(String file) throws Exception {
        new InitService().initialize(request(tempDir, "opencode", InitProgress.noop(), InitReporter.noop()));
        Path worker = tempDir.resolve(".opencode/agents/camel-ship-worker.md");
        assertTrue(Files.exists(worker), "precondition: worker generated by the first init");

        // OpenCode loads the last "mcp" value (introducing "probe"); Kit rejects the duplicate key
        Path config = tempDir.resolve(file);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"mcp\":{},\"mcp\":{\"probe\":{\"type\":\"local\",\"command\":[\"echo\"]}}}");
        String configBefore = Files.readString(config);

        assertThrows(InvalidAgentConfigurationException.class,
                () -> new InitService()
                        .initialize(request(tempDir, "opencode", InitProgress.noop(), InitReporter.noop())));

        assertFalse(Files.exists(worker), "worker without probe_*: deny must not survive a rejected reinit");
        assertEquals(configBefore, Files.readString(config), "user configuration must be left untouched");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void failedPreflightDoesNotDeleteShipWorkerThroughSymlinkedAgentsDirectory() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = outside.resolve("camel-ship-worker.md");
        Files.writeString(sentinel, "outside");
        Files.createDirectories(tempDir.resolve(".opencode"));
        Files.createSymbolicLink(tempDir.resolve(".opencode/agents"), outside);
        Files.writeString(tempDir.resolve("opencode.json"), "{\"mcp\":{env:MCP_SERVERS}}");
        List<String> outsideBefore = snapshot(outside);

        IOException failure = assertThrows(IOException.class,
                () -> new InitService()
                        .initialize(request(tempDir, "opencode", InitProgress.noop(), InitReporter.noop())));

        assertTrue(failure.getMessage().contains("symbolic link"));
        assertEquals(outsideBefore, snapshot(outside), "cleanup must not follow the agents symlink");
        assertTrue(Files.isSymbolicLink(tempDir.resolve(".opencode/agents")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSymlinkedAgentRootsBeforeWritingOutsideTheProject() throws Exception {
        for (String agentName : List.of("claude", "bob2", "opencode", "antigravity")) {
            Path targetDir = Files.createDirectory(tempDir.resolve(agentName));
            Path outside = Files.createDirectory(tempDir.resolve(agentName + "-outside"));
            Files.writeString(outside.resolve("keep.md"), "outside");
            if ("opencode".equals(agentName)) {
                Files.writeString(outside.resolve("opencode.jsonc"), "{} {}");
            }
            String agentRoot = switch (agentName) {
                case "claude" -> ".claude";
                case "bob2" -> ".bob";
                case "antigravity" -> ".agents";
                default -> ".opencode";
            };
            Files.createSymbolicLink(targetDir.resolve(agentRoot), outside);
            List<String> before = snapshot(outside);

            IOException failure = assertThrows(
                    IOException.class,
                    () -> new InitService().initialize(
                            request(targetDir, agentName, InitProgress.noop(), InitReporter.noop())));

            assertTrue(failure.getMessage().contains("symbolic link"), agentName);
            assertTrue(failure.getMessage().contains("Initialization aborted"),
                    agentName + " must state that initialization was aborted, not a file removal");
            assertEquals(before, snapshot(outside), agentName + " must not write through the agent-root link");
            assertFalse(Files.exists(targetDir.resolve(".camel-kit")),
                    agentName + " must fail before writing any project files");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsSymlinkedLegacyCommandDirectoryBeforeWriting() throws Exception {
        Path targetDir = Files.createDirectory(tempDir.resolve("claude-legacy"));
        Path outside = Files.createDirectory(tempDir.resolve("claude-legacy-outside"));
        Files.writeString(outside.resolve("camel-start.md"), "legacy stub");
        Files.createDirectories(targetDir.resolve(".claude"));
        Files.createSymbolicLink(targetDir.resolve(".claude/commands"), outside);
        List<String> before = snapshot(outside);

        IOException failure = assertThrows(
                IOException.class,
                () -> new InitService().initialize(
                        request(targetDir, "claude", InitProgress.noop(), InitReporter.noop())));

        assertTrue(failure.getMessage().contains("symbolic link"));
        assertTrue(failure.getMessage().contains("Initialization aborted"));
        assertEquals(before, snapshot(outside), "Must not write through the legacy command directory link");
        assertFalse(Files.exists(targetDir.resolve(".camel-kit")),
                "Must fail before writing any project files");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void normalizesTheTargetBeforeCleanupAndGeneration() throws Exception {
        Path targetDir = Files.createDirectory(tempDir.resolve("orders"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path outsideChild = Files.createDirectory(outside.resolve("child"));
        Files.writeString(outside.resolve("keep.md"), "outside");
        Files.createSymbolicLink(targetDir.resolve("link"), outsideChild);
        List<String> before = snapshot(outside);

        InitResult result = new InitService().initialize(
                request(targetDir.resolve("link/.."), "claude", InitProgress.noop(), InitReporter.noop()));

        assertEquals(targetDir.toAbsolutePath().normalize(), result.targetDir());
        assertTrue(Files.isRegularFile(targetDir.resolve(".claude/skills/camel-ship/SKILL.md")));
        assertEquals(before, snapshot(outside));
    }

    @Test
    void initializesCopilotWorkspaceWithGithubNativeAssets() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        Path targetDir = tempDir.resolve("orders");

        InitResult result = new InitService().initialize(
                request(targetDir, "copilot", progress, InitReporter.noop()));

        assertEquals("copilot", result.agentName());
        assertTrue(Files.isRegularFile(targetDir.resolve(".github/copilot-instructions.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".github/mcp.json")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".github/skills/camel-start/SKILL.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".github/agents/camel-implementer.agent.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".github/hooks/camel-kit-safety.json")));
        assertFalse(Files.exists(targetDir.resolve(".github/commands")));
        assertTrue(progress.events().contains("start:Generating GitHub Copilot CLI workspace"));

        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("agent.name=copilot"));
        assertTrue(config.contains("agent.folder=.github/skills"));
    }

    @Test
    void initializesPiWorkspaceWithNativeAssets() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        Path targetDir = tempDir.resolve("orders");

        InitResult result = new InitService().initialize(
                request(targetDir, "pi", progress, InitReporter.noop()));

        assertEquals("pi", result.agentName());
        assertTrue(Files.isRegularFile(targetDir.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".mcp.json")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".pi/skills/camel-start/SKILL.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".pi/prompts/camel-start.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".pi/extensions/camel-kit-guard.ts")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".pi/camel-kit-guard-policy.json")));
        assertTrue(progress.events().contains("start:Generating Pi workspace"));

        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("agent.name=pi"));
        assertTrue(config.contains("agent.folder=.pi/prompts"));
    }

    @Test
    void initializesCodexWorkspaceWithNativeAssetsWithoutCommandStubs() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        Path targetDir = tempDir.resolve("orders");

        InitResult result = new InitService().initialize(
                request(targetDir, "codex", progress, InitReporter.noop()));

        assertEquals("codex", result.agentName());
        assertTrue(Files.isRegularFile(targetDir.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".codex/config.toml")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".agents/skills/camel-start/SKILL.md")));
        assertTrue(Files.isRegularFile(targetDir.resolve(".codex/agents/camel-implementer.toml")));
        assertFalse(Files.exists(targetDir.resolve(".codex/commands")));
        assertFalse(Files.exists(targetDir.resolve(".agents/commands")));
        assertTrue(progress.events().contains("start:Generating OpenAI Codex CLI workspace"));

        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("agent.name=codex"));
        assertTrue(config.contains("agent.folder=.agents/skills"));
    }

    @Test
    void initPersistsRuntimeAndVersionConfig() throws Exception {
        Path targetDir = tempDir.resolve("orders");

        new InitService().initialize(request(targetDir, "bob2", InitProgress.noop(), InitReporter.noop()));

        Properties config = new Properties();
        try (var in = Files.newInputStream(targetDir.resolve(".camel-kit/config.properties"))) {
            config.load(in);
        }
        DistributionConfig distribution = CamelKitMain.distribution();
        assertEquals("main", config.getProperty("project.runtime"));
        assertEquals(distribution.camelMainVersion(), config.getProperty("project.camelVersion"));
        assertEquals(distribution.camelMainVersion(), config.getProperty("project.platformBomVersion"));
        assertNull(config.getProperty("project.springBootVersion"),
                "spring-boot framework version must only be written for the spring-boot runtime");
    }

    @Test
    void unknownAgentFailsBeforeWritingWorkspace() {
        Path targetDir = tempDir.resolve("orders");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new InitService().initialize(
                        request(targetDir, "unknown-agent", InitProgress.noop(), InitReporter.noop())));

        assertEquals("Unknown agent 'unknown-agent'", error.getMessage());
        assertFalse(Files.exists(targetDir));
    }

    @Test
    void invalidOpenCodeConfigFailsBeforeWritingAnyWorkspaceFiles() throws Exception {
        Path targetDir = Files.createDirectory(tempDir.resolve("orders"));
        Files.writeString(targetDir.resolve("opencode.json"), "{} {}");
        List<String> before = snapshot(targetDir);

        InvalidAgentConfigurationException failure = assertThrows(
                InvalidAgentConfigurationException.class,
                () -> new InitService().initialize(
                        request(targetDir, "opencode", InitProgress.noop(), InitReporter.noop())));

        assertTrue(failure.getMessage().contains("existing opencode.json"));
        assertEquals(before, snapshot(targetDir));
        assertFalse(Files.exists(targetDir.resolve("AGENTS.md")));
        assertFalse(Files.exists(targetDir.resolve(".camel-kit")));
        assertFalse(Files.exists(targetDir.resolve(".opencode")));
    }

    @Test
    void invalidOpenCodeConfigCandidateFailsBeforeWritingAnyWorkspaceFiles() throws Exception {
        List<String> configFiles = List.of(
                "opencode.json",
                "opencode.jsonc",
                ".opencode/opencode.json",
                ".opencode/opencode.jsonc");
        for (int i = 0; i < configFiles.size(); i++) {
            Path targetDir = Files.createDirectory(tempDir.resolve("orders-" + i));
            Path validFile = targetDir.resolve("opencode.json".equals(configFiles.get(i))
                    ? "opencode.jsonc"
                    : "opencode.json");
            Files.writeString(validFile, "{}");
            Path invalidFile = targetDir.resolve(configFiles.get(i));
            Files.createDirectories(invalidFile.getParent());
            Files.writeString(invalidFile, "{} {}");
            List<String> before = snapshot(targetDir);

            InvalidAgentConfigurationException failure = assertThrows(
                    InvalidAgentConfigurationException.class,
                    () -> new InitService().initialize(
                            request(targetDir, "opencode", InitProgress.noop(), InitReporter.noop())),
                    configFiles.get(i));

            assertTrue(failure.getMessage().contains(configFiles.get(i)), failure.getMessage());
            assertEquals(before, snapshot(targetDir), configFiles.get(i));
            assertFalse(Files.exists(targetDir.resolve("AGENTS.md")), configFiles.get(i));
            assertFalse(Files.exists(targetDir.resolve(".camel-kit")), configFiles.get(i));
        }
    }

    @Test
    void nonRegularOpenCodeConfigFailsBeforeWritingAnyWorkspaceFiles() throws Exception {
        Path targetDir = Files.createDirectory(tempDir.resolve("orders"));
        Files.createDirectory(targetDir.resolve("opencode.json"));
        List<String> before = snapshot(targetDir);

        InvalidAgentConfigurationException failure = assertThrows(
                InvalidAgentConfigurationException.class,
                () -> new InitService().initialize(
                        request(targetDir, "opencode", InitProgress.noop(), InitReporter.noop())));

        assertEquals("existing opencode.json must be a regular file", failure.getMessage());
        assertEquals(before, snapshot(targetDir));
    }

    @Test
    void structurallyInvalidOpenCodeConfigFailsBeforeWritingAnyWorkspaceFiles() throws Exception {
        Path targetDir = Files.createDirectory(tempDir.resolve("orders-structural"));
        Files.writeString(targetDir.resolve("opencode.json"), "{\"permission\": []}");
        List<String> before = snapshot(targetDir);

        InvalidAgentConfigurationException failure = assertThrows(
                InvalidAgentConfigurationException.class,
                () -> new InitService().initialize(
                        request(targetDir, "opencode", InitProgress.noop(), InitReporter.noop())));

        assertEquals("existing opencode.json field 'permission' must be an object", failure.getMessage());
        assertEquals(before, snapshot(targetDir));
        assertFalse(Files.exists(targetDir.resolve("AGENTS.md")));
        assertFalse(Files.exists(targetDir.resolve(".camel-kit")));
        assertFalse(Files.exists(targetDir.resolve(".opencode")));
    }

    @Test
    void customCitrusVersionDoesNotChangeMcpServerArtifactVersion() throws Exception {
        Path targetDir = tempDir.resolve("orders");

        InitResult result = new InitService().initialize(
                request(targetDir, "bob2", "4.9.2", CamelKitMain.distribution(), true, true,
                        InitProgress.noop(), InitReporter.noop()));

        assertEquals("4.9.2", result.citrusVersion());
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("citrus.version=4.9.2"));
        assertTrue(config.contains("citrus.mcp.version=" + EXPECTED_CITRUS_MCP_VERSION));
        assertTrue(config.contains("citrus.mcp.enabled=true"));
        String mcp = Files.readString(targetDir.resolve(".bob/mcp.json"));
        assertTrue(mcp.contains(
                "org.citrusframework:citrus-mcp-server:" + EXPECTED_CITRUS_MCP_VERSION + ":runner"));
        assertFalse(mcp.contains("org.citrusframework:citrus-mcp-server:4.9.2:runner"));
    }

    @Test
    void explicitCitrusMcpVersionOverrideIsUsedWithCustomCitrusVersion() throws Exception {
        Path targetDir = tempDir.resolve("orders");
        Properties properties = new Properties();
        properties.setProperty("citrus.mcp.version", "4.10.1");
        DistributionConfig distribution = DistributionConfig.load(properties);

        InitResult result = new InitService().initialize(
                request(targetDir, "bob2", "4.9.2", distribution, true, true,
                        InitProgress.noop(), InitReporter.noop()));

        assertEquals("4.9.2", result.citrusVersion());
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("citrus.version=4.9.2"));
        assertTrue(config.contains("citrus.mcp.version=4.10.1"));
        assertTrue(config.contains("citrus.mcp.enabled=true"));
        String mcp = Files.readString(targetDir.resolve(".bob/mcp.json"));
        assertTrue(mcp.contains("org.citrusframework:citrus-mcp-server:4.10.1:runner"));
        assertFalse(mcp.contains("org.citrusframework:citrus-mcp-server:4.9.2:runner"));
    }

    @Test
    void forageVersionMappedToDefaultCamelVersionIsWrittenToConfig() throws Exception {
        Path targetDir = tempDir.resolve("orders");
        Properties properties = new Properties();
        properties.setProperty("camel.main.version", "9.9.9");
        properties.setProperty("forage.version.9.9.9", "9.1");
        DistributionConfig distribution = DistributionConfig.load(properties);

        // pre-seeded cache keeps the unit test offline (isCached short-circuits the download);
        // noFetch=false so the caching path actually runs and the offline guard below stays meaningful
        Path forageCacheDir = targetDir.resolve(".camel-kit/.cache/forage/9.1");
        Files.createDirectories(forageCacheDir);
        Files.writeString(forageCacheDir.resolve("forage-catalog.json"), "{}");
        Files.writeString(forageCacheDir.resolve("forage-configuration-catalog.json"), "{}");

        new InitService().initialize(
                request(targetDir, "bob2", "default", distribution, false, InitProgress.noop(), InitReporter.noop()));

        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("forage.version=9.1"));

        // guard: if the cache short-circuit stopped working, the real download would overwrite
        // these dummy files with actual catalog JSON — asserting content stays "{}" proves no
        // network call happened.
        assertEquals("{}", Files.readString(forageCacheDir.resolve("forage-catalog.json")));
        assertEquals("{}", Files.readString(forageCacheDir.resolve("forage-configuration-catalog.json")));
    }

    @Test
    void noFetchSkipsForageCatalogCaching() throws Exception {
        Path targetDir = tempDir.resolve("orders");
        Properties properties = new Properties();
        properties.setProperty("camel.main.version", "9.9.9");
        properties.setProperty("forage.version.9.9.9", "9.1");
        DistributionConfig distribution = DistributionConfig.load(properties);

        new InitService().initialize(
                request(targetDir, "bob2", "default", distribution, InitProgress.noop(), InitReporter.noop()));

        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("forage.version=9.1"), "version mapping is still written with --no-fetch");
        assertFalse(Files.exists(targetDir.resolve(".camel-kit/.cache/forage")),
                "--no-fetch must not attempt any catalog download");
    }

    @Test
    void runtimeDetectionRecomputesForageVersionForDetectedRuntime() throws Exception {
        Path targetDir = tempDir.resolve("orders");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.camel.quarkus</groupId>
                      <artifactId>camel-quarkus-core</artifactId>
                      <version>3.15.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Properties properties = new Properties();
        properties.setProperty("camel.main.version", "9.9.9");
        properties.setProperty("camel.quarkus.version", "8.8.8");
        properties.setProperty("forage.version.9.9.9", "9.1");
        properties.setProperty("forage.version.8.8.8", "8.1");
        DistributionConfig distribution = DistributionConfig.load(properties);

        new InitService().initialize(
                request(targetDir, "bob2", "default", distribution, InitProgress.noop(), InitReporter.noop()));

        Properties config = new Properties();
        try (var in = Files.newInputStream(targetDir.resolve(".camel-kit/config.properties"))) {
            config.load(in);
        }
        assertEquals("quarkus", config.getProperty("project.runtime"));
        assertEquals("8.1", config.getProperty("forage.version"),
                "forage.version must track the runtime-detected Camel version, not the initial main default");
    }

    private InitRequest request(
            Path targetDir,
            String agentName,
            InitProgress progress,
            InitReporter reporter) {
        return new InitRequest(
                "orders",
                agentName,
                targetDir,
                "default",
                true,
                false,
                "mulesoft",
                "camel-kit",
                "5.0.1",
                CamelKitMain.distribution(),
                Printer.noop(),
                progress,
                reporter,
                false,
                false);
    }

    private InitRequest requestForExistingProject(
            Path targetDir,
            String agentName,
            InitProgress progress,
            InitReporter reporter) {
        return new InitRequest(
                "orders",
                agentName,
                targetDir,
                "default",
                true,
                false,
                null, // No sourcePlatform - this is an existing Camel project, not a migration
                "camel-kit",
                "5.0.1",
                CamelKitMain.distribution(),
                Printer.noop(),
                progress,
                reporter,
                false,
                false);
    }

    private static List<String> snapshot(Path root) throws Exception {
        List<String> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString();
                entries.add(Files.isDirectory(path)
                        ? "directory:" + relative
                        : "file:" + relative + "=" + Files.readString(path));
            }
        }
        return entries;
    }

    private InitRequest requestWithCitrusMcp(Path targetDir, String agentName) {
        return new InitRequest(
                "orders",
                agentName,
                targetDir,
                "default",
                true,
                true,
                "mulesoft",
                "camel-kit",
                "5.0.1",
                CamelKitMain.distribution(),
                Printer.noop(),
                InitProgress.noop(),
                InitReporter.noop(),
                false,
                false);
    }

    private InitRequest request(
            Path targetDir,
            String agentName,
            String citrusVersion,
            InitProgress progress,
            InitReporter reporter) {
        return request(targetDir, agentName, citrusVersion, CamelKitMain.distribution(), progress, reporter);
    }

    private InitRequest request(
            Path targetDir,
            String agentName,
            String citrusVersion,
            DistributionConfig distribution,
            InitProgress progress,
            InitReporter reporter) {
        return request(targetDir, agentName, citrusVersion, distribution, true, false, progress, reporter);
    }

    private InitRequest request(
            Path targetDir,
            String agentName,
            String citrusVersion,
            DistributionConfig distribution,
            boolean noFetch,
            InitProgress progress,
            InitReporter reporter) {
        return request(targetDir, agentName, citrusVersion, distribution, noFetch, false, progress, reporter);
    }

    private InitRequest request(
            Path targetDir,
            String agentName,
            String citrusVersion,
            DistributionConfig distribution,
            boolean noFetch,
            boolean withCitrusMcp,
            InitProgress progress,
            InitReporter reporter) {
        return new InitRequest(
                "orders",
                agentName,
                targetDir,
                citrusVersion,
                noFetch,
                withCitrusMcp,
                "mulesoft",
                "camel-kit",
                "5.0.1",
                distribution,
                Printer.noop(),
                progress,
                reporter,
                false,
                false);
    }

    private static final class RecordingProgress implements InitProgress {
        private final List<String> events = new ArrayList<>();

        @Override
        public void startTask(String icon, String label) {
            events.add("start:" + label);
        }

        @Override
        public void finishTask() {
            events.add("finish");
        }

        List<String> events() {
            return events;
        }
    }

    private static final class RecordingReporter implements InitReporter {
        private final List<InitWarning> warnings = new ArrayList<>();
        private String mavenVersion;
        private InitGraphSummary graph;
        private boolean graphSkipped;

        @Override
        public void mavenWrapperCreated(String mavenVersion) {
            this.mavenVersion = mavenVersion;
        }

        @Override
        public void graphBuilt(InitGraphSummary graph) {
            this.graph = graph;
        }

        @Override
        public void graphSkipped() {
            graphSkipped = true;
        }

        @Override
        public void warning(InitWarning warning) {
            warnings.add(warning);
        }

        String mavenVersion() {
            return mavenVersion;
        }

        InitGraphSummary graph() {
            return graph;
        }

        boolean wasGraphSkipped() {
            return graphSkipped;
        }

        List<InitWarning> warnings() {
            return warnings;
        }

        boolean hasWarningContaining(String text) {
            return warnings.stream().anyMatch(warning -> warning.message().contains(text));
        }
    }

    // Existing Project Preservation Tests (Issue #164)

    @Test
    void initOverExistingProjectPreservesPomXml() throws Exception {
        // Setup: Create existing Camel project with pom.xml
        Path targetDir = tempDir.resolve("existing");
        Files.createDirectories(targetDir);
        String originalPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-existing-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.camel</groupId>
                            <artifactId>camel-core</artifactId>
                            <version>4.8.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(targetDir.resolve("pom.xml"), originalPom);

        // Act: Run init (using requestForExistingProject which sets sourcePlatform=null)
        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: pom.xml unchanged
        String afterInit = Files.readString(targetDir.resolve("pom.xml"));
        assertEquals(originalPom, afterInit, "pom.xml should not be modified during init");

        // Assert: .camel-kit and skills were created
        assertTrue(Files.exists(targetDir.resolve(".camel-kit/config.properties")));
        assertTrue(Files.exists(targetDir.resolve(".claude/skills")));

        // Assert: project.type=existing in config
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("project.type=existing"));
    }

    @Test
    void initSkipsMavenWrapperWhenItExists() throws Exception {
        // Setup: Create existing Camel project with Maven wrapper
        Path targetDir = tempDir.resolve("existing-wrapper");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"),
                "<project><dependencies><dependency><groupId>org.apache.camel</groupId><artifactId>camel-core</artifactId></dependency></dependencies></project>");
        Files.writeString(targetDir.resolve("mvnw"), "#!/bin/sh\n# Existing wrapper");
        Files.createDirectories(targetDir.resolve(".mvn/wrapper"));
        Files.writeString(targetDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://example.com/maven-3.8.0.zip");

        String originalMvnw = Files.readString(targetDir.resolve("mvnw"));
        String originalProps = Files.readString(targetDir.resolve(".mvn/wrapper/maven-wrapper.properties"));

        // Act: Run init (using requestForExistingProject which sets sourcePlatform=null)
        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: Maven wrapper unchanged
        assertEquals(originalMvnw, Files.readString(targetDir.resolve("mvnw")));
        assertEquals(originalProps, Files.readString(targetDir.resolve(".mvn/wrapper/maven-wrapper.properties")));
    }

    @Test
    void initReplacesWrapperWhenExplicitlyRequested() throws Exception {
        // Setup: Create existing project with Maven wrapper
        Path targetDir = tempDir.resolve("replace-wrapper");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), "<project/>");
        Files.writeString(targetDir.resolve("mvnw"), "#!/bin/sh\n# Old wrapper");

        // Act: Run init with replaceWrapper=true
        InitRequest requestWithReplace = new InitRequest(
                "orders",
                "claude",
                targetDir,
                "default",
                true,
                false,
                "mulesoft",
                "camel-kit",
                "5.0.1",
                CamelKitMain.distribution(),
                Printer.noop(),
                InitProgress.noop(),
                InitReporter.noop(),
                false,
                true); // replaceWrapper = true
        new InitService().initialize(requestWithReplace);

        // Assert: Maven wrapper was replaced
        String newMvnw = Files.readString(targetDir.resolve("mvnw"));
        assertFalse(newMvnw.contains("# Old wrapper"));
        assertTrue(newMvnw.contains("mvnw")); // Contains new wrapper content
    }

    @Test
    void reinitPreservesUserCustomizationsInAgentsMd() throws Exception {
        // Setup: Init once
        Path targetDir = tempDir.resolve("reinit-test");
        Files.createDirectories(targetDir);
        new InitService().initialize(request(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // User adds custom content to AGENTS.md
        String agentsMdPath = targetDir.resolve("AGENTS.md").toString();
        String originalContent = Files.readString(targetDir.resolve("AGENTS.md"));
        String userCustomization = "\n\n# My Custom Section\n\nThis is my custom content.";
        Files.writeString(targetDir.resolve("AGENTS.md"), originalContent + userCustomization);

        // Act: Reinit
        new InitService().initialize(request(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: User customization preserved
        String afterReinit = Files.readString(targetDir.resolve("AGENTS.md"));
        assertTrue(afterReinit.contains("# My Custom Section"),
                "User customization should be preserved during reinit");
        assertTrue(afterReinit.contains("This is my custom content."),
                "User content should not be lost");

        // Assert: Generated content appears exactly once (no duplication)
        String generatedMarker = "Camel-Kit AI Agent Guidelines";
        int firstOccurrence = afterReinit.indexOf(generatedMarker);
        int secondOccurrence = afterReinit.indexOf(generatedMarker, firstOccurrence + 1);
        assertEquals(-1, secondOccurrence,
                "Generated content should appear exactly once, not duplicated on reinit");

        // Assert: Managed block markers present
        assertTrue(afterReinit.contains("<!-- CAMEL-KIT-MANAGED-BLOCK-START: camel-kit-generated -->"),
                "Managed block start marker should be present");
        assertTrue(afterReinit.contains("<!-- CAMEL-KIT-MANAGED-BLOCK-END: camel-kit-generated -->"),
                "Managed block end marker should be present");
    }

    @Test
    void reinitPreservesUserCustomizationsInClaudeMd() throws Exception {
        // Setup: Init once
        Path targetDir = tempDir.resolve("reinit-claude-test");
        Files.createDirectories(targetDir);
        new InitService().initialize(request(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // User adds custom content to CLAUDE.md
        String originalContent = Files.readString(targetDir.resolve("CLAUDE.md"));
        String userCustomization = "\n\n## My Custom Documentation\n\nCustom project-specific notes here.";
        Files.writeString(targetDir.resolve("CLAUDE.md"), originalContent + userCustomization);

        // Act: Reinit
        new InitService().initialize(request(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: User customization preserved
        String afterReinit = Files.readString(targetDir.resolve("CLAUDE.md"));
        assertTrue(afterReinit.contains("## My Custom Documentation"),
                "User customization should be preserved in CLAUDE.md during reinit");
        assertTrue(afterReinit.contains("Custom project-specific notes here."),
                "User content should not be lost in CLAUDE.md");

        // Assert: Generated content appears exactly once (no duplication)
        String generatedMarker = "# Camel-Kit";
        int firstOccurrence = afterReinit.indexOf(generatedMarker);
        int secondOccurrence = afterReinit.indexOf(generatedMarker, firstOccurrence + 1);
        assertEquals(-1, secondOccurrence,
                "Generated content in CLAUDE.md should appear exactly once, not duplicated on reinit");

        // Assert: Managed block markers present
        assertTrue(afterReinit.contains("<!-- CAMEL-KIT-MANAGED-BLOCK-START: camel-kit-generated -->"),
                "CLAUDE.md managed block start marker should be present");
        assertTrue(afterReinit.contains("<!-- CAMEL-KIT-MANAGED-BLOCK-END: camel-kit-generated -->"),
                "CLAUDE.md managed block end marker should be present");
    }

    @Test
    void dryRunShowsConflictsWithoutWriting() throws Exception {
        // Setup: Create existing project
        Path targetDir = tempDir.resolve("dry-run-test");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("AGENTS.md"), "# Existing AGENTS.md");
        Files.writeString(targetDir.resolve("pom.xml"), "<project/>");

        // Act: Run init with dryRun=true
        InitRequest dryRunRequest = new InitRequest(
                "orders",
                "claude",
                targetDir,
                "default",
                true,
                false,
                "mulesoft",
                "camel-kit",
                "5.0.1",
                CamelKitMain.distribution(),
                Printer.noop(),
                InitProgress.noop(),
                InitReporter.noop(),
                true, // dryRun = true
                false);
        InitResult result = new InitService().initialize(dryRunRequest);

        // Assert: No .camel-kit directory created (dry-run didn't write)
        assertFalse(Files.exists(targetDir.resolve(".camel-kit/config.properties")),
                "Dry-run should not create config.properties");

        // Assert: AGENTS.md unchanged
        String afterDryRun = Files.readString(targetDir.resolve("AGENTS.md"));
        assertEquals("# Existing AGENTS.md", afterDryRun,
                "Dry-run should not modify existing files");
    }

    @Test
    void muleProjectIsNotMarkedAsExisting() throws Exception {
        // Setup: Create MuleSoft project with pom.xml
        Path targetDir = tempDir.resolve("mule-project");
        Files.createDirectories(targetDir);
        String mulePom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-mule-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.mule.runtime</groupId>
                            <artifactId>mule-core</artifactId>
                            <version>4.4.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(targetDir.resolve("pom.xml"), mulePom);

        // Act: Run init (using requestForExistingProject which sets sourcePlatform=null)
        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: NOT marked as existing (MuleSoft is a migration source, not existing Camel)
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("project.type=greenfield"),
                "MuleSoft project should be marked as greenfield (migration target), not existing");
        assertFalse(config.contains("project.type=existing"),
                "MuleSoft project should NOT be marked as existing");
    }

    @Test
    void existingProjectWithUndetectableVersionThrowsError() throws Exception {
        // Setup: Create existing Camel project but with no version information
        Path targetDir = tempDir.resolve("no-version-project");
        Files.createDirectories(targetDir);
        // Create a minimal pom with Camel but no detectable version
        // Dependencies have no version = will be parsed as "managed" by PomParser
        String pomWithoutVersion = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>

                    <dependencies>
                        <dependency>
                            <groupId>org.apache.camel</groupId>
                            <artifactId>camel-core</artifactId>
                            <!-- No version - parser sets to "managed" -->
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(targetDir.resolve("pom.xml"), pomWithoutVersion);

        // Act & Assert: Should throw IllegalStateException, not fall back to defaults
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new InitService().initialize(
                        requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop())));

        // Assert: Error message is actionable
        assertTrue(exception.getMessage().contains("Cannot detect Camel version"),
                "Error should mention version detection failure");
        assertTrue(exception.getMessage().contains("camel.version property"),
                "Error should suggest adding camel.version property");
        assertTrue(exception.getMessage().contains("-p project.camelVersion"),
                "Error should suggest -p flag");
        assertTrue(exception.getMessage().contains("Refusing to guess"),
                "Error should explicitly refuse to guess");
    }

    @Test
    void existingProjectWithUnsupportedCamelVersionWarnsTheUser() throws Exception {
        Path targetDir = tempDir.resolve("camel3-warning");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), minimalCamelPom("3.21.0"));
        RecordingReporter reporter = new RecordingReporter();

        new InitService().initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), reporter));

        assertTrue(reporter.hasWarningContaining("Camel 3.21.0 is not supported"),
                "the user must be told on the command line, not only through a config flag");
        Properties config = loadConfig(targetDir);
        assertEquals("true", config.getProperty("project.unsupportedVersion"));
        assertEquals("3.21.0", config.getProperty("project.camelVersion"),
                "the detected version must be kept, not replaced by a supported default");
    }

    @Test
    void existingProjectWithoutAnyRoutesWarnsThatTheBaselineIsIncomplete() throws Exception {
        Path targetDir = tempDir.resolve("no-routes");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), minimalCamelPom("4.8.0"));
        RecordingReporter reporter = new RecordingReporter();

        new InitService().initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), reporter));

        Properties config = loadConfig(targetDir);
        assertEquals("existing", config.getProperty("project.type"));
        assertNull(config.getProperty("project.routeCount"), "no routes were discovered, so no count is claimed");
        assertNull(config.getProperty("project.primaryDsl"), "no DSL may be guessed without route files");
        assertFalse(Files.exists(targetDir.resolve(".camel-kit/baseline-routes.txt")));
        assertTrue(reporter.hasWarningContaining("No routes were found"),
                "an incomplete graph must be reported instead of silently producing an empty baseline");
        assertTrue(config.getProperty("project.discoveryGaps").contains("No routes were found"),
                "the gap must be persisted so the workflow inspects the source instead of assuming");
        assertNull(config.getProperty("project.unsupportedCombination"));
    }

    @Test
    void existingProjectWithOnlyJavaDslRoutesIsReportedAsUnsupportedAndKeepsItsDsl() throws Exception {
        Path targetDir = tempDir.resolve("java-dsl-only");
        Path src = Files.createDirectories(targetDir.resolve("src/main/java/x"));
        Files.writeString(targetDir.resolve("pom.xml"), minimalCamelPom("4.8.0"));
        Files.writeString(src.resolve("Routes.java"), """
                package x;
                import org.apache.camel.builder.RouteBuilder;
                public class Routes extends RouteBuilder {
                    public void configure() { from("timer:t").routeId("t").to("log:x"); }
                }
                """);
        RecordingReporter reporter = new RecordingReporter();

        new InitService().initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), reporter));

        Properties config = loadConfig(targetDir);
        assertEquals("java", config.getProperty("project.primaryDsl"), "the detected DSL is kept, never converted");
        assertEquals("4.8.0", config.getProperty("project.camelVersion"));
        assertTrue(config.getProperty("project.unsupportedCombination").contains("No YAML routes were found"));
        assertTrue(reporter.hasWarningContaining("No YAML routes were found"));
    }

    @Test
    void existingProjectInventoriesContractFilesAndSkipsBuildOutput() throws Exception {
        Path fixtureSource = Path.of("src/test/resources/fixtures/existing-project-spring-boot");
        Path targetDir = tempDir.resolve("contracts");
        copyDirectory(fixtureSource, targetDir);
        Files.createDirectories(targetDir.resolve("src/main/resources/api"));
        Files.writeString(targetDir.resolve("src/main/resources/api/openapi.yaml"), "openapi: 3.0.0\n");
        Files.writeString(targetDir.resolve("src/main/resources/orders.xsd"), "<xs:schema/>");
        Files.createDirectories(targetDir.resolve("target/classes"));
        Files.writeString(targetDir.resolve("target/classes/stale.wsdl"), "<definitions/>");

        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        assertEquals("2", loadConfig(targetDir).getProperty("project.contractFilesCount"));
        assertEquals(List.of("src/main/resources/api/openapi.yaml", "src/main/resources/orders.xsd"),
                Files.readAllLines(targetDir.resolve(".camel-kit/baseline-contracts.txt")));
    }

    @Test
    void existingProjectWithoutContractFilesWritesNoContractBaseline() throws Exception {
        Path fixtureSource = Path.of("src/test/resources/fixtures/existing-project-spring-boot");
        Path targetDir = tempDir.resolve("no-contracts");
        copyDirectory(fixtureSource, targetDir);

        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        assertNull(loadConfig(targetDir).getProperty("project.contractFilesCount"));
        assertFalse(Files.exists(targetDir.resolve(".camel-kit/baseline-contracts.txt")));
    }

    /**
     * Scripted end-to-end check of the init side of the workflow on the representative Spring Boot fixture: every
     * constraint downstream stages read is recorded, the existing sources are untouched, and nothing is guessed.
     */
    @Test
    void existingProjectEndToEndRecordsConstraintsBaselineAndLeavesSourcesUntouched() throws Exception {
        Path fixtureSource = Path.of("src/test/resources/fixtures/existing-project-spring-boot");
        Path targetDir = tempDir.resolve("e2e");
        copyDirectory(fixtureSource, targetDir);
        Map<String, String> before = contentSnapshot(targetDir);

        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        Properties config = loadConfig(targetDir);
        assertEquals("existing", config.getProperty("project.type"));
        assertEquals("spring-boot", config.getProperty("project.runtime"));
        assertEquals("4.8.0", config.getProperty("project.camelVersion"));
        assertEquals("yaml", config.getProperty("project.primaryDsl"));
        assertEquals("3", config.getProperty("project.routeCount"));
        assertEquals("not_run", config.getProperty("project.baselineTestStatus"));
        assertNull(config.getProperty("project.unsupportedVersion"));
        assertNull(config.getProperty("project.unsupportedCombination"));
        assertNull(config.getProperty("project.discoveryGaps"));
        assertTrue(Files.readAllLines(targetDir.resolve(".camel-kit/baseline-routes.txt")).stream()
                .allMatch(f -> f.endsWith(".yaml")));
        assertFalse(Files.readAllLines(targetDir.resolve(".camel-kit/baseline-tests.txt")).isEmpty());

        // no pre-existing file was modified or removed (dependency versions and DSL stay exactly as they were)
        Map<String, String> after = contentSnapshot(targetDir);
        before.forEach((file, content) -> assertEquals(content, after.get(file),
                "init must not change the existing project file " + file));
    }

    private static Map<String, String> contentSnapshot(Path root) throws java.io.IOException {
        Map<String, String> files = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString(), Files.readString(p));
            }
        }
        return files;
    }

    @Test
    void existingProjectWithYamlRoutesHasNoUnsupportedCombinationOrGaps() throws Exception {
        Path fixtureSource = Path.of("src/test/resources/fixtures/existing-project-spring-boot");
        Path targetDir = tempDir.resolve("supported-fixture");
        copyDirectory(fixtureSource, targetDir);

        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        Properties config = loadConfig(targetDir);
        assertNull(config.getProperty("project.unsupportedCombination"));
        assertNull(config.getProperty("project.discoveryGaps"));
    }

    @Test
    void existingProjectWithMalformedPomReportsAWarningAndStillInitializes() throws Exception {
        Path targetDir = tempDir.resolve("broken-pom");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), "<project><dependencies><dependency>");
        RecordingReporter reporter = new RecordingReporter();

        InitResult result = new InitService().initialize(
                requestForExistingProject(targetDir, "claude", InitProgress.noop(), reporter));

        assertNotNull(result);
        assertFalse(reporter.warnings().isEmpty(), "an unreadable pom must surface as a warning");
        assertTrue(Files.exists(targetDir.resolve(".camel-kit/config.properties")));
    }

    @Test
    void baselineTestsAreNotRunUnlessRequested() throws Exception {
        Path targetDir = projectWithFakeWrapper("baseline-default", 0);

        new InitService().initialize(
                requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        assertFalse(Files.exists(targetDir.resolve("mvnw-was-run")),
                "init must not execute project build code unless --record-baseline is given");
        assertEquals("not_run", loadConfig(targetDir).getProperty("project.baselineTestStatus"));
        assertTrue(Files.readString(targetDir.resolve(".camel-kit/baseline-test-results.json"))
                .contains("\"status\": \"not_run\""));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void recordBaselineRecordsPassedWhenTheProjectTestsPass() throws Exception {
        Path targetDir = projectWithFakeWrapper("baseline-pass", 0);

        new InitService().initialize(recordingBaselineRequest(targetDir));

        assertTrue(Files.exists(targetDir.resolve("mvnw-was-run")), "the wrapper must have been invoked");
        assertEquals("passed", loadConfig(targetDir).getProperty("project.baselineTestStatus"));
        assertTrue(Files.readString(targetDir.resolve(".camel-kit/baseline-test-results.json"))
                .contains("\"status\": \"passed\""));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void recordBaselineRecordsFailedWhenTheProjectTestsFail() throws Exception {
        Path targetDir = projectWithFakeWrapper("baseline-fail", 1);

        new InitService().initialize(recordingBaselineRequest(targetDir));

        assertEquals("failed", loadConfig(targetDir).getProperty("project.baselineTestStatus"));
        assertTrue(Files.readString(targetDir.resolve(".camel-kit/baseline-test-results.json"))
                .contains("\"status\": \"failed\""));
    }

    @Test
    void existingProjectWithUnsupportedCamelVersionIsDetected() throws Exception {
        // Setup: Create existing project with Camel 3.x (unsupported)
        Path targetDir = tempDir.resolve("camel3-project");
        Files.createDirectories(targetDir);
        String camel3Pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>legacy-app</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <camel.version>3.21.0</camel.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.camel</groupId>
                            <artifactId>camel-core</artifactId>
                            <version>${camel.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(targetDir.resolve("pom.xml"), camel3Pom);

        // Act: Run init
        InitResult result = new InitService().initialize(
                requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: Detected as existing project with unsupported version
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("project.type=existing"), "Should detect as existing project");
        assertTrue(config.contains("project.camelVersion=3.21.0"), "Should detect Camel 3.21.0");
        assertTrue(config.contains("project.unsupportedVersion=true"),
                "Should flag Camel 3.x as unsupported");
        assertTrue(config.contains("Camel 3.x is not supported"),
                "Should provide reason for unsupported flag");
    }

    @Test
    void greenfieldProjectWithoutCamelIsNotMarkedAsExisting() throws Exception {
        // Setup: Create greenfield project with pom.xml but no Camel artifacts
        Path targetDir = tempDir.resolve("greenfield-maven");
        Files.createDirectories(targetDir);
        String plainPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        Files.writeString(targetDir.resolve("pom.xml"), plainPom);

        // Act: Run init
        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: NOT marked as existing (no Camel artifacts)
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("project.type=greenfield"),
                "Maven project without Camel should be marked as greenfield, not existing");
        assertFalse(config.contains("project.type=existing"),
                "Maven project without Camel should NOT be marked as existing");
    }

    @Test
    void existingProjectConfigContainsDetectedMetadata() throws Exception {
        // Setup: Copy fixture to temp directory
        Path fixtureSource = Path.of("src/test/resources/fixtures/existing-project-spring-boot");
        Path targetDir = tempDir.resolve("fixture-test");

        // Fixture must exist - fail test if missing (don't skip silently)
        assertTrue(Files.exists(fixtureSource),
                "Test fixture missing at " + fixtureSource + " - existing project tests cannot run");

        copyDirectory(fixtureSource, targetDir);

        // Act: Run init (using requestForExistingProject which sets sourcePlatform=null)
        new InitService()
                .initialize(requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: Config contains detected metadata
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("project.type=existing"), "Should detect existing project");
        assertTrue(config.contains("project.detectedCamelVersion=4.8.0"), "Should detect Camel version");

        // CRITICAL: project.camelVersion must be bound to detected version, not distribution default
        assertTrue(config.contains("project.camelVersion=4.8.0"),
                "project.camelVersion must use detected version for existing projects");

        assertTrue(config.contains("project.runtime=spring-boot"), "Should detect Spring Boot runtime");
        assertTrue(config.contains("project.primaryDsl=yaml"), "Should detect YAML DSL");
        assertTrue(config.contains("project.hasTests=true"), "Should detect existing tests");
        assertTrue(config.contains("project.routeCount=3"), "Should count existing routes");
        assertTrue(config.contains("project.configFilesCount=1"),
                "The fixture has exactly one application.properties");
        assertFalse(config.contains("project.moduleCount"), "The fixture is a single-module project");
    }

    @Test
    void reinitUpdatesSkillManifestsAndGuides() throws Exception {
        // Setup: Init once
        Path targetDir = tempDir.resolve("skill-update-test");
        Files.createDirectories(targetDir);
        new InitService().initialize(request(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Find a SKILL.md file
        Path skillManifest = targetDir.resolve(".claude/skills/camel-start/SKILL.md");
        assertTrue(Files.exists(skillManifest), "SKILL.md should exist after first init");
        String originalSkillContent = Files.readString(skillManifest);

        // Find a guide file
        Path guideFile = targetDir.resolve(".claude/skills/camel-brainstorm/guides/version-selection.md");
        if (Files.exists(guideFile)) {
            String originalGuideContent = Files.readString(guideFile);

            // Modify both files to simulate old versions
            Files.writeString(skillManifest, "# OLD VERSION\n" + originalSkillContent);
            Files.writeString(guideFile, "# OLD GUIDE\n" + originalGuideContent);

            // Act: Reinit
            new InitService().initialize(request(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

            // Assert: SKILL.md and guide files are updated (not preserved)
            String updatedSkillContent = Files.readString(skillManifest);
            assertFalse(updatedSkillContent.contains("# OLD VERSION"),
                    "SKILL.md should be updated on reinit, not preserved");

            String updatedGuideContent = Files.readString(guideFile);
            assertFalse(updatedGuideContent.contains("# OLD GUIDE"),
                    "Guide files should be updated on reinit, not preserved");
        }

        // Create a custom file in skills directory (not SKILL.md or guide)
        Path customFile = targetDir.resolve(".claude/skills/camel-start/my-custom-notes.md");
        String customContent = "# My Custom Notes\n\nThese are my project-specific notes.";
        Files.writeString(customFile, customContent);

        // Act: Reinit again
        new InitService().initialize(request(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Assert: Custom files are preserved (not overwritten)
        assertTrue(Files.exists(customFile), "Custom skill file should be preserved on reinit");
        String preservedCustomContent = Files.readString(customFile);
        assertEquals(customContent, preservedCustomContent,
                "Custom skill files should be preserved exactly, not overwritten on reinit");
    }

    @Test
    void e2eExistingProjectWorkflow() throws Exception {
        // This is the core acceptance test: complete workflow on existing project fixture
        // Tests init, adding capability, running tests, verifying preservation
        Path fixtureSource = Path.of("src/test/resources/fixtures/existing-project-spring-boot");
        Path targetDir = tempDir.resolve("e2e-workflow");

        // Fixture must exist
        assertTrue(Files.exists(fixtureSource),
                "E2E test fixture missing at " + fixtureSource);

        copyDirectory(fixtureSource, targetDir);

        // Capture original state
        String originalPom = Files.readString(targetDir.resolve("pom.xml"));
        String originalCamelVersion = extractMavenProperty(originalPom, "camel.version");
        String originalSpringBootVersion = extractMavenProperty(originalPom, "spring-boot.version");
        List<String> originalRouteFiles = listYamlFiles(targetDir.resolve("src/main/resources/camel"));
        int originalRouteCount = originalRouteFiles.size();

        // Step 1: Init existing project
        InitResult initResult = new InitService().initialize(
                requestForExistingProject(targetDir, "claude", InitProgress.noop(), InitReporter.noop()));

        // Verify: Detection worked
        String config = Files.readString(targetDir.resolve(".camel-kit/config.properties"));
        assertTrue(config.contains("project.type=existing"), "Should detect existing project");
        assertTrue(config.contains("project.camelVersion=4.8.0"), "Should detect Camel 4.8.0");
        assertTrue(config.contains("project.runtime=spring-boot"), "Should detect Spring Boot");
        assertTrue(config.contains("project.primaryDsl=yaml"), "Should detect YAML DSL");
        assertTrue(config.contains("project.dslTypes=YAML"), "Should record all DSL types");
        assertTrue(config.contains("project.routeCount=3"), "Should count 3 existing routes");

        // Verify: Baseline files created for regression verification (#169 requirement)
        Path baselineRoutes = targetDir.resolve(".camel-kit/baseline-routes.txt");
        assertTrue(Files.exists(baselineRoutes), "Should create baseline-routes.txt for regression checks");
        // baseline-routes.txt lists route files (3 routes live in 2 files); routeCount counts routes
        assertEquals(List.of("src/main/resources/camel/api-routes.yaml", "src/main/resources/camel/timer-routes.yaml"),
                Files.readAllLines(baselineRoutes),
                "Should record each existing route file exactly once");

        Path baselineTests = targetDir.resolve(".camel-kit/baseline-tests.txt");
        assertTrue(Files.exists(baselineTests), "Should create baseline-tests.txt for regression checks");
        List<String> recordedTests = Files.readAllLines(baselineTests);
        assertTrue(recordedTests.size() > 0, "Should record existing test files");

        // Verify: pom.xml unchanged after init
        String pomAfterInit = Files.readString(targetDir.resolve("pom.xml"));
        assertEquals(originalPom, pomAfterInit, "pom.xml should not be modified by init");

        // Step 2: Add a new capability (real YAML route that would work with existing routes)
        // Use unique route ID to avoid conflicts with existing routes
        Path newRouteFile = targetDir.resolve("src/main/resources/camel/monitoring-routes.yaml");
        String newRouteContent = """
                - route:
                    id: monitoring-heartbeat
                    description: Monitoring heartbeat capability added to existing project
                    from:
                      uri: timer:monitoring
                      parameters:
                        period: 60000
                      steps:
                        - log:
                            message: "Monitoring heartbeat: system OK"
                        - setBody:
                            constant: "Status: OK"
                """;
        Files.writeString(newRouteFile, newRouteContent);

        // A test proving the new capability is actually loaded and started by the existing application
        Path newCapabilityTest = targetDir.resolve(
                "src/test/java/org/apache/camel/kit/test/fixture/MonitoringRouteTest.java");
        Files.writeString(newCapabilityTest,
                """
                        package org.apache.camel.kit.test.fixture;

                        import org.apache.camel.CamelContext;
                        import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
                        import org.junit.jupiter.api.Test;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.boot.test.context.SpringBootTest;

                        import static org.junit.jupiter.api.Assertions.assertTrue;

                        @CamelSpringBootTest
                        @SpringBootTest
                        public class MonitoringRouteTest {

                            @Autowired
                            private CamelContext camelContext;

                            @Test
                            public void monitoringHeartbeatIsStarted() {
                                assertTrue(camelContext.getRouteController().getRouteStatus("monitoring-heartbeat").isStarted());
                            }
                        }
                        """);

        // Step 3: Run the project's own tests with the wrapper that init installed.
        // Explicitly skip (not pass) when Maven cannot run here, e.g. offline CI without access to Maven Central.
        Path mvnw = targetDir.resolve("mvnw");
        assertTrue(Files.exists(mvnw), "init must install the Maven wrapper into a project that has none");
        mvnw.toFile().setExecutable(true);
        Path probeLog = tempDir.resolve("mvnw-probe.log");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                runMaven(targetDir, probeLog, 120, "-v") == 0,
                "Skipping: Maven wrapper cannot run in this environment (see " + probeLog + ")");

        Path testLog = tempDir.resolve("mvnw-test.log");
        int exitCode = runMaven(targetDir, testLog, 600, "test", "-B", "-q");
        if (exitCode != 0) {
            fail("Maven tests failed (exit code " + exitCode + ").\n"
                 + "This indicates the new capability broke existing tests or has issues.\n"
                 + "Maven output:\n" + Files.readString(testLog));
        }
        assertTrue(
                Files.exists(targetDir.resolve(
                        "target/surefire-reports/TEST-org.apache.camel.kit.test.fixture.MonitoringRouteTest.xml")),
                "The new capability test must actually have run");

        // Tests passed - verify all expectations
        // Verify: pom.xml still unchanged after tests
        String pomAfterTests = Files.readString(targetDir.resolve("pom.xml"));
        assertEquals(originalPom, pomAfterTests,
                "pom.xml should remain unchanged after adding capability and running tests");

        // Verify: Versions not upgraded
        String currentCamelVersion = extractMavenProperty(pomAfterTests, "camel.version");
        String currentSpringBootVersion = extractMavenProperty(pomAfterTests, "spring-boot.version");
        assertEquals(originalCamelVersion, currentCamelVersion,
                "Camel version should not be upgraded");
        assertEquals(originalSpringBootVersion, currentSpringBootVersion,
                "Spring Boot version should not be upgraded");

        // Verify: All original routes still exist
        List<String> finalRouteFiles = listYamlFiles(targetDir.resolve("src/main/resources/camel"));
        assertEquals(originalRouteCount + 1, finalRouteFiles.size(),
                "Should have original routes + new capability route");
        for (String routeFile : originalRouteFiles) {
            assertTrue(finalRouteFiles.contains(routeFile),
                    "Original route file should still exist: " + routeFile);
        }

        // SUCCESS: E2E test passed - init, add capability, tests pass, preservation verified

        // Verify: New capability added
        assertTrue(Files.exists(newRouteFile), "New route file should be created");

        // Verify: Original files still unchanged
        List<String> routeFilesAfterAddition = listYamlFiles(targetDir.resolve("src/main/resources/camel"));
        assertEquals(originalRouteCount + 1, routeFilesAfterAddition.size(),
                "Should have original routes + 1 new route");

        for (String routeFile : originalRouteFiles) {
            assertTrue(routeFilesAfterAddition.contains(routeFile),
                    "Original route file should still exist after adding new capability: " + routeFile);
        }

        // Verify: Skills and workspace created
        assertTrue(Files.exists(targetDir.resolve(".claude/skills")),
                "Skills directory should be created");
        assertTrue(Files.exists(targetDir.resolve("AGENTS.md")),
                "AGENTS.md should be created");
        assertTrue(Files.exists(targetDir.resolve("CLAUDE.md")),
                "CLAUDE.md should be created");
    }

    private static String minimalCamelPom(String camelVersion) {
        return String.format(java.util.Locale.ROOT, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <camel.version>%s</camel.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.camel</groupId>
                            <artifactId>camel-core</artifactId>
                            <version>${camel.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.0</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """, camelVersion);
    }

    private static Properties loadConfig(Path targetDir) throws IOException {
        Properties config = new Properties();
        try (var in = Files.newInputStream(targetDir.resolve(".camel-kit/config.properties"))) {
            config.load(in);
        }
        return config;
    }

    /**
     * An existing Camel project whose Maven wrapper is a stub that records its invocation and exits with the given
     * code, so baseline behaviour can be checked without running a real build.
     */
    private Path projectWithFakeWrapper(String name, int exitCode) throws IOException {
        Path targetDir = tempDir.resolve(name);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), minimalCamelPom("4.8.0"));
        Path mvnw = targetDir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\ntouch mvnw-was-run\nexit " + exitCode + "\n");
        mvnw.toFile().setExecutable(true);
        return targetDir;
    }

    private InitRequest recordingBaselineRequest(Path targetDir) {
        return new InitRequest(
                "orders", "claude", targetDir, "default", true, false, null, "camel-kit", "5.0.1",
                CamelKitMain.distribution(), Printer.noop(), InitProgress.noop(), InitReporter.noop(),
                false, false, true);
    }

    /**
     * Runs the project's Maven wrapper with output redirected to a file (no pipe to fill) and returns the exit code; a
     * run that exceeds the timeout is killed and reported as -1.
     */
    private static int runMaven(Path projectDir, Path log, int timeoutSeconds, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("./mvnw");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private String extractMavenProperty(String pomXml, String propertyName) {
        // Simple regex to extract <propertyName>value</propertyName>
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<" + propertyName + ">([^<]+)</" + propertyName + ">");
        java.util.regex.Matcher matcher = pattern.matcher(pomXml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> listYamlFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .map(p -> directory.relativize(p).toString())
                    .sorted()
                    .toList();
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            for (Path sourcePath : paths.toList()) {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath);
                }
            }
        }
    }
}
