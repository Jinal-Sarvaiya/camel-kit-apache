package org.apache.camel.kit.workflow;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.ToolProvider;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.doc.FrontmatterHandler;
import org.apache.camel.kit.graph.GraphSerializer;
import org.apache.camel.kit.graph.parser.GraphParser;
import org.apache.camel.kit.graph.parser.MuleXmlFlowParser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SkillPackCompilerTest {

    @TempDir
    Path temp;
    private static final String REVIEW = "List the routes that the validation run covered";
    private static final String SKILL = """
            ---
            name: camel-inspect
            description: Inspect an integration.
            user_invocable: true
            ---
            Original instructions.
            """;

    @Test
    void defaultCatalogPreservesPublicCommandsInternalSkillsAndCoreResources() throws Exception {
        var catalog = SkillCatalog.bundled();
        WorkflowManifest workflow = catalog.manifest().workflow();
        assertEquals(WorkflowManifestLoader.load(WorkflowManifestLoader.DEFAULT_RESOURCE), workflow);
        assertEquals(Set.of("camel-start", "camel-brainstorm", "camel-plan", "camel-migrate", "camel-execute",
                "camel-validate", "camel-debug", "camel-ship", "camel-knowledge"),
                workflow.generatedCommandStubs().stream().map(c -> c.name()).collect(Collectors.toSet()));
        assertEquals(Set.of("camel-test", "camel-design", "camel-implement", "camel-verify"), workflow.skills().stream()
                .filter(s -> "internal".equals(s.status())).map(s -> s.name()).collect(Collectors.toSet()));
        assertEquals("camel-validate/SKILL.md",
                catalog.manifest().contributions().get("step/validate/report/timestamp").path());
        assertTrue(content(catalog, "camel-validate/SKILL.md").contains("Use the current date and time"));
        assertFalse(content(catalog, "camel-start/SKILL.md").contains("<!-- camel-kit:step"));
        assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/skills/shared/iron-laws.md")),
                catalog.open("shared/iron-laws.md").readAllBytes());
    }

    @Test
    void claudePreservesExistingCommandsThatShareAPackSkillName() throws Exception {
        Path pack = pack("inspect", "add: {skill/camel-inspect: {contract: 1, resource: inspect.md}}");
        write("inspect", "inspect.md", SKILL);
        Path distribution = distribution(pack, "inspect");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path commands = Files.createDirectories(workspace.resolve(".claude/commands"));
        Files.writeString(commands.resolve("camel-inspect.md"), "My project command.");
        Files.writeString(commands.resolve("camel-start.md"), "My command at a former Kit path.");
        assertCli(0, distribution, workspace, "init", "--here", "--ai", "claude", "--no-fetch");
        assertEquals("My project command.", Files.readString(commands.resolve("camel-inspect.md")));
        assertEquals("My command at a former Kit path.", Files.readString(commands.resolve("camel-start.md")));
        assertTrue(Files.isRegularFile(workspace.resolve(".claude/skills/camel-inspect/SKILL.md")));
        String doctor = assertCli(1, distribution, workspace, "doctor");
        assertTrue(doctor.contains(".claude/commands/camel-start.md"), doctor);
        Files.delete(commands.resolve("camel-start.md"));
        assertCli(0, distribution, workspace, "doctor");
    }

    @ParameterizedTest
    @ValueSource(strings = {"camel-design", "camel-implement", "camel-test"})
    void survivingInstructionsRequireGuidesFromExcludedSkills(String skill) throws Exception {
        Path pack
                = pack("minimal", "requires: [core]\nreplace: {skill/camel-start: {contract: 1, resource: router.md}}\n"
                                  + "exclude: [skill/" + skill + "]");
        write("minimal", "router.md", SKILL.replace("camel-inspect", "camel-start"));
        try (var loader = loader(pack)) {
            var error = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("minimal")));
            assertTrue(error.getMessage().contains("resource/" + skill + "/guides/"), error::getMessage);
            assertTrue(error.getMessage().contains("minimal: skill/" + skill), error::getMessage);
        }
    }

    @Test
    void excludingOneGuideReportsItsSurvivingConsumerAndTheExcludingPack() throws Exception {
        String guide = "resource/camel-design/guides/performance.md";
        Path pack = pack("small", "requires: [core]\nexclude: [" + guide + "]");
        try (var loader = loader(pack)) {
            var error = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("small")));
            assertTrue(error.getMessage().contains("core: "), error::getMessage);
            assertTrue(error.getMessage().contains("requires missing contribution " + guide), error::getMessage);
            assertTrue(error.getMessage().contains("small: " + guide), error::getMessage);
        }
    }

    @Test
    void coreMarkdownReferencesToOtherSkillsGuidesAreCatalogDependencies() throws Exception {
        var catalog = SkillCatalog.bundled();
        var bindings = catalog.manifest().contributions();
        Path sources = Path.of("src/main/resources/skills");
        for (var entry : bindings.entrySet()) {
            if (!(entry.getKey().startsWith("skill/") || entry.getKey().startsWith("resource/"))) {
                continue;
            }
            String path = entry.getValue().path();
            if (!path.endsWith(".md")) {
                continue;
            }
            String text = Files.readString(sources.resolve(path));
            String owner = path.substring(0, path.indexOf('/') + 1);
            for (String guide : catalog.paths()) {
                if (guide.contains("/guides/") && !guide.startsWith(owner) && text.contains(guide)) {
                    assertTrue(entry.getValue().requires().contains("resource/" + guide), path + " -> " + guide);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"camel-start", "camel-inspect"})
    void replacingASkillMustPreserveItsDeclaredVisibility(String name) throws Exception {
        Path upstream = pack("inspect", "add: {skill/camel-inspect: {contract: 1, resource: entry.md}}");
        write("inspect", "entry.md", SKILL);
        Path overlay = pack("team", "requires: [core, inspect]\nreplace: {skill/" + name
                                    + ": {contract: 1, resource: entry.md}}");
        write("team", "entry.md", SKILL.replace("camel-inspect", name).replace("true", "false"));
        try (var loader = loader(upstream, overlay)) {
            var failure = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("inspect", "team")));
            String reason = name.equals("camel-start")
                    ? "user_invocable differs from the core skill contract"
                    : "replacement must preserve visibility declared by inspect: skill/camel-inspect";
            assertEquals("team: skill/" + name + ": " + reason, failure.getMessage());
        }
    }

    @Test
    void replacingTheConsumerAllowsExcludingItsGuide() throws Exception {
        Path upstream = pack("inspect", """
                add:
                  skill/camel-inspect: {contract: 1, resource: entry.md}
                  resource/camel-inspect/guides/check.md: {contract: 1, resource: check.md}
                """);
        write("inspect", "entry.md", SKILL + "Read `guides/check.md`.\n");
        write("inspect", "check.md", "Check the integration.");
        Path overlay = pack("team", """
                requires: [inspect]
                replace: {skill/camel-inspect: {contract: 1, resource: entry.md}}
                exclude: [resource/camel-inspect/guides/check.md]
                """);
        write("team", "entry.md", SKILL);
        try (var loader = loader(upstream, overlay)) {
            assertDoesNotThrow(() -> SkillPackCompiler.compile(loader, List.of("inspect", "team")));
        }
    }

    @Test
    void aPublicSkillNeedsARoutingMarkerInTheReplacementRouter() throws Exception {
        Path pack = pack("team", """
                requires: [core]
                add: {skill/camel-inspect: {contract: 1, resource: entry.md}}
                replace: {skill/camel-start: {contract: 1, resource: router.md}}
                """);
        write("team", "entry.md", SKILL);
        write("team", "router.md", SKILL.replace("camel-inspect", "camel-start"));
        try (var loader = loader(pack)) {
            var failure = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("team")));
            assertEquals(
                    "team: skill/camel-start: expected one <!-- camel-kit:step choose --> marker for route/choose in camel-start/SKILL.md",
                    failure.getMessage());
        }
    }

    @Test
    @Tag("cli-compat")
    void codexAdaptsCommandMentionsAndPreservesLiteralPaths() throws Exception {
        Path pack = pack("docs", "add: {skill/docs: {contract: 1, resource: docs.md}, "
                                 + "skill/camel-inspect: {contract: 1, resource: inspect.md}}");
        write("docs", "inspect.md", SKILL);
        String paths = "`/docs/index.md`, `/camel-inspect/notes.md`, `/camel-inspect.md`, "
                       + "`/docs_config`, `https://example.org/docs` and `[notes](/docs)`.";
        String literal = paths + "\n[manual]: /camel-inspect?view=raw\n[section]: /camel-inspect#overview"
                         + "\n[plain]: /camel-inspect\n[inline]( /camel-inspect) and /camel-inspect?view=raw"
                         + "\n> [quoted]: /docs\n1. [ordered]: </docs>\n> - [nested]:\n>   /camel-inspect"
                         + "\n`~/docs` and `/camel-inspect*.md`"
                         + "\nrm /docs*\n`ls /docs**`"
                         + "\n> - [escaped\\] label]: /docs 'Reference title'"
                         + "\n[quote]: /docs \"A \\\"quoted\\\" title\"\n[paren]: /docs (A \\(nested\\) title)"
                         + "\n**Step**: run /docs* next\r*Step*: run /docs* next\r\nrm /docs**";
        String prose
                = "Run /docs, then /camel-inspect... **/camel-inspect**? |/docs| /docs<br> Try /docs? ~~ /docs ~~ and ~~/docs~~.";
        prose += "\n- [Optional]: /docs to refresh\n[]: /docs\n[   ]: /docs\n*Use /docs*";
        prose += "\n[x]: /docs \"a\" b \"c\"\n[y]: /docs 'a' b 'c'\n[z]: /docs (a) b (c)";
        prose += "\n***Use /docs** and /docs*\n*Use *this* /docs*";
        prose += "\n\\**Use /docs*\n\\***Use /docs**\n\\\\*Use /docs*";
        literal += "\n\\*Use /docs*\n\\\\\\*Use /docs*";
        String example = "[" + "{\"id\":1,\"route\":\"inventory\"},".repeat(2000) + "{\"id\":2}]";
        literal += "\n" + "rm /docs*\n".repeat(20_000);
        write("docs", "docs.md", SKILL.replace("camel-inspect", "docs") + prose + "\n" + literal + "\n" + example);
        Path distribution = distribution(pack, "docs");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        assertCli(0, distribution, workspace, "init", "--here", "--ai", "codex", "--no-fetch");
        assertTrue(Files.readString(workspace.resolve(".agents/skills/docs/SKILL.md"))
                .contains(
                        prose.replace("/docs", "$docs").replace("/camel-inspect", "$camel-inspect")
                          + "\n" + literal + "\n" + example));
    }

    private Path withoutKnowledge(String survivingCaller) throws IOException {
        StringBuilder replacements = new StringBuilder();
        for (String name : List.of("camel-start", "camel-brainstorm", "camel-execute", "camel-migrate")) {
            if (!name.equals(survivingCaller)) {
                replacements.append("  skill/").append(name).append(": {contract: 1, resource: ").append(name)
                        .append(".md}\n");
                boolean router = name.equals("camel-start");
                write("team", name + ".md",
                        SKILL.replace("camel-inspect", name).replace("true", Boolean.toString(router))
                                            + (router ? "<!-- camel-kit:step choose -->\n" : ""));
            }
        }
        write("team", "inspect.md", SKILL);
        return pack("team", "requires: [core]\nreplace:\n" + replacements
                            + "exclude: [skill/camel-knowledge, skill/camel-ship]\n"
                            + "add: {skill/camel-inspect: {contract: 1, resource: inspect.md}}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"camel-brainstorm", "camel-execute", "camel-migrate"})
    void eachCoreKnowledgeHandoffRequiresTheSkill(String caller) throws Exception {
        try (var loader = loader(withoutKnowledge(caller))) {
            var error = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("team")));
            assertTrue(
                    error.getMessage().contains(
                            "core: skill/" + caller + ": requires missing contribution skill/camel-knowledge"),
                    error::getMessage);
            assertTrue(error.getMessage().contains("team: skill/camel-knowledge"), error::getMessage);
        }
    }

    @Test
    void checklistReadersDependOnTheChecklist() throws Exception {
        var bindings = SkillCatalog.bundled().manifest().contributions();
        String checklist = "resource/shared/camel-security-checklist.md";
        for (String guide : List.of("security", "monitoring")) {
            String reader = "resource/camel-design/guides/" + guide + ".md";
            assertTrue(bindings.get(reader).requires().contains(checklist), reader);
            assertFalse(bindings.get(checklist).requires().contains(reader), reader);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void artifactOwnersAreRequiredEvenWithoutAStage(boolean producer) throws Exception {
        String name = "camel-knowledge";
        String key = "skill/" + name;
        List<String> owners = List.of(name);
        var artifact = new WorkflowManifest.WorkflowArtifact(
                "report", "report.md",
                producer ? owners : List.of(), producer ? List.of() : owners);
        var core = WorkflowManifestLoader.load(WorkflowManifestLoader.DEFAULT_RESOURCE);
        var artifacts = new ArrayList<>(core.artifacts());
        artifacts.add(artifact);
        var workflow = new WorkflowManifest(
                core.version(), core.description(), core.commands(), core.skills(),
                core.stages(), artifacts, core.mcpServers(), core.websiteReference());
        WorkflowManifestValidator.validateOrThrow(workflow);
        var overlay = new SkillPack("team", 1, List.of("core"), Map.of(), Map.of(), List.of(key));
        var original
                = new SkillPack.Declaration(
                        "core", key, new SkillPack.Entry(1, "entry.md", null, List.of(), null, List.of()));
        var failure = assertThrows(IOException.class,
                () -> SkillPack.resolve(Map.of("team", overlay), Map.of(key, original), workflow));
        assertEquals("team: " + key + ": required by core workflow artifact report", failure.getMessage());
    }

    @Test
    void installedContextUsesEffectivePublicSkills() throws Exception {
        Path distribution = distribution(withoutKnowledge(""), "team");
        String output = assertJava(0, distribution, temp, InstallAgents.class.getName());
        assertFalse(output.contains("/camel-knowledge"), output);
        for (String agent : AgentRegistry.names()) {
            Path workspace = temp.resolve(agent);
            for (String file : List.of("AGENTS.md", "CLAUDE.md", ".github/copilot-instructions.md")) {
                Path path = workspace.resolve(file);
                if (Files.exists(path)) {
                    String context = Files.readString(path);
                    assertFalse(context.contains("/camel-knowledge") || context.contains("Use `camel-knowledge`"),
                            agent + "/" + file);
                    assertFalse(context.contains("/camel-ship") || context.contains("$camel-ship"), agent + "/" + file);
                }
            }
            if (!List.of("codex", "pi").contains(agent)) {
                assertTrue(Files.readString(workspace.resolve("AGENTS.md")).contains("camel-inspect"), agent);
            }
            String router = Files.readString(
                    workspace.resolve(AgentRegistry.get(agent).skillsDirectory()).resolve("camel-start/SKILL.md"));
            assertTrue(router.contains("camel-inspect"), agent);
            assertFalse(router.contains("camel-knowledge"), agent);
        }
        String modes = Files.readString(temp.resolve("bob2/.bob/custom_modes.yaml"));
        assertFalse(modes.contains("camel-ship-mode"));
        assertTrue(modes.contains("camel-brainstorm-mode"));
        assertTrue(Files.isRegularFile(temp.resolve("bob2/.bob/agents/camel-ship-worker.md")));
    }

    @Test
    @Tag("cli-compat")
    void allHostsReconcilePublicAndInternalPackEntriesOnUpgrade() throws Exception {
        String entries = """
                add:
                  skill/camel-inspect: {contract: 1, resource: entry.md}
                  skill/camel-reader: {contract: 1, resource: reader.md}
                  resource/camel-reader/guides/GUIDE.md: {contract: 1, resource: guide.md}
                """;
        for (String version : List.of("first", "second")) {
            Path pack = pack(version, entries.replace("GUIDE", version));
            write(version, "entry.md", SKILL.replace("Original", version));
            write(version, "reader.md", SKILL.replace("camel-inspect", "camel-reader").replace("true", "false"));
            write(version, "guide.md", version);
            Path distribution = distribution(pack, version);
            assertJava(0, distribution, temp, InstallAgents.class.getName(), "--force");
            for (String name : AgentRegistry.names()) {
                var agent = AgentRegistry.get(name);
                Path workspace = temp.resolve(name);
                Path skills = workspace.resolve(agent.skillsDirectory());
                assertTrue(
                        Files.readString(skills.resolve("camel-inspect/SKILL.md")).contains(version + " instructions."),
                        name);
                assertEquals(version, Files.readString(skills.resolve("camel-reader/guides/" + version + ".md")), name);
                if (version.equals("second")) {
                    assertFalse(Files.exists(skills.resolve("camel-reader/guides/first.md")), name);
                }
                var header = SkillPack.YAML.readTree(FrontmatterHandler.extractFrontmatterYaml(
                        Files.readString(skills.resolve("camel-reader/SKILL.md"))));
                assertTrue(header.has("user_invocable") && !header.get("user_invocable").booleanValue(), name);
                if (Set.of("claude", "copilot", "pi").contains(name)) {
                    assertFalse(header.get("user-invocable").booleanValue(), name);
                    assertTrue(header.get("disable-model-invocation").booleanValue(), name);
                }
                if (agent.generatesCommandStubs()) {
                    Path commands = workspace.resolve(agent.commandDirectory());
                    String stub = Files.readString(commands.resolve("camel-inspect." + agent.fileFormat()));
                    assertTrue(stub.contains(agent.skillsDirectory() + "/camel-inspect/SKILL.md"), name);
                    if (agent.argPlaceholder() != null) {
                        assertTrue(stub.contains(agent.argPlaceholder()), name);
                    }
                    assertFalse(Files.exists(commands.resolve("camel-reader." + agent.fileFormat())), name);
                }
            }
        }
    }

    /** All hosts use one JVM and one assembled catalog, isolated from the default catalog in the test runner. */
    public static class InstallAgents {
        public static void main(String[] args) {
            for (String agent : AgentRegistry.names()) {
                var options = new ArrayList<>(List.of("init", agent, "--ai", agent, "--no-fetch"));
                options.addAll(List.of(args));
                String[] init = options.toArray(String[]::new);
                assertEquals(0, CamelKitMain.commandLine(new CamelKitMain(), init).execute(init), agent);
                String[] doctor = {"doctor", "--project-dir", agent};
                assertEquals(0, CamelKitMain.commandLine(new CamelKitMain(), doctor).execute(doctor), agent);
            }
        }
    }

    private Path distribution(Path pack, String id) throws Exception {
        Path distribution = temp.resolve("dist-" + id);
        Path archive = distribution.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(pack)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of(id)));
        }
        return distribution;
    }

    @Test
    @Tag("cli-compat")
    void reinitializationRemovesOnlyUnmodifiedFilesFromThePreviousCatalog() throws Exception {
        Path source = pack("optional", """
                add:
                  skill/camel-optional: {contract: 1, resource: entry.md}
                  resource/camel-optional/guides/owned.md: {contract: 1, resource: guide.md}
                  resource/camel-optional/guides/edited.md: {contract: 1, resource: guide.md}
                  resource/camel-optional/guides/example.yaml: {contract: 1, resource: example.yaml}
                  resource/camel-optional/guides/legacy.md: {contract: 1, resource: legacy.md}
                  resource/camel-optional/guides/opaque.bin: {contract: 1, resource: opaque.bin}
                  resource/camel-optional/guides/original.bin: {contract: 1, resource: opaque.bin}
                  resource/camel-optional/guides/setup.sh: {contract: 1, resource: guide.md}
                  resource/camel-optional/guides/mapping.xsl: {contract: 1, resource: guide.md}
                  resource/camel-optional/guides/text.bin: {contract: 1, resource: guide.md}
                """);
        write("optional", "entry.md", SKILL.replace("camel-inspect", "camel-optional"));
        write("optional", "guide.md", "Original guide.\n");
        write("optional", "example.yaml", "title: example\r\nitems: []\n");
        write("optional", "legacy.md", "Older guide.\r\n");
        byte[] binary = {0, (byte) 0xff, '\r', '\n'};
        Files.write(source.resolve(SkillPack.directory("optional")).resolve("opaque.bin"), binary);
        Path before = distribution(source, "optional");
        Path overlay = pack("minimal", "requires: [optional]\nexclude: [skill/camel-optional]");
        Path after = temp.resolve("minimal-dist");
        Path archive = after.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(source, overlay)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("optional", "minimal")));
        }
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path skill = workspace.resolve(".bob/skills/camel-optional");
        Path stub = workspace.resolve(".bob/commands/camel-optional.md");
        assertCli(0, before, workspace, "init", "--here", "--ai", "bob2", "--no-fetch", "--silent");
        Path ledger = workspace.resolve(".camel-kit/managed-files.properties");
        Properties records = new Properties();
        try (var in = Files.newBufferedReader(ledger)) {
            records.load(in);
        }
        // The previous writer recorded raw CRLF bytes even when Git later checked the file out with LF.
        Path legacy = skill.resolve("guides/legacy.md");
        records.setProperty("catalog/.bob/skills/camel-optional/guides/legacy.md",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(legacy))));
        try (var out = Files.newBufferedWriter(ledger)) {
            records.store(out, null);
        }
        Files.writeString(legacy, "Older guide.\n");
        Files.writeString(stub, Files.readString(stub).replace("\r\n", "\n").replace("\n", "\r\n"));
        for (String name : List.of("setup.sh", "mapping.xsl")) {
            Files.writeString(skill.resolve("guides").resolve(name), "Original guide.\r\n");
        }
        Files.writeString(skill.resolve("guides/example.yaml"), "title: example\r\nitems: []\r\n");
        binary[1] = (byte) 0xfe;
        Files.write(skill.resolve("guides/opaque.bin"), binary);
        Files.writeString(skill.resolve("guides/text.bin"), "Original guide.\r\n");
        Files.writeString(skill.resolve("guides/edited.md"), "My edits.");
        Files.writeString(skill.resolve("guides/user.md"), "My own guide.");
        assertTrue(Files.isRegularFile(stub));
        String preview = assertCli(0, after, workspace, "init", "--here", "--ai", "bob2", "--dry-run");
        assertPreview(preview, "remove", ".bob/commands/camel-optional.md");
        for (String name : List.of("original.bin", "setup.sh", "mapping.xsl")) {
            assertPreview(preview, "remove", ".bob/skills/camel-optional/guides/" + name);
        }
        assertPreview(preview, "remove", ".bob/skills/camel-optional/guides/example.yaml");
        assertPreview(preview, "remove", ".bob/skills/camel-optional/guides/legacy.md");
        assertPreview(preview, "preserve", ".bob/skills/camel-optional/guides/edited.md");
        assertPreview(preview, "preserve", ".bob/skills/camel-optional/guides/opaque.bin");
        assertPreview(preview, "preserve", ".bob/skills/camel-optional/guides/text.bin");
        assertTrue(preview.contains("--replace <path>"));
        assertTrue(Files.isRegularFile(stub));
        assertTrue(Files.isRegularFile(skill.resolve("SKILL.md")));
        for (int i = 0; i < 2; i++) {
            String report = assertCli(0, after, workspace, "init", "--here", "--ai", "bob2", "--force", "--no-fetch");
            var notices = report.lines().filter(line -> line.contains(".bob/skills/camel-optional/guides/edited.md"))
                    .toList();
            assertEquals(1, notices.size(), report);
            assertTrue(notices.getFirst().contains("review and move or remove it manually"), report);
            for (String name : List.of("original.bin", "setup.sh", "mapping.xsl")) {
                assertFalse(Files.exists(skill.resolve("guides").resolve(name)), name);
            }
            assertFalse(Files.exists(stub));
            assertFalse(Files.exists(skill.resolve("SKILL.md")));
            assertFalse(Files.exists(skill.resolve("guides/owned.md")));
            assertFalse(Files.exists(skill.resolve("guides/example.yaml")));
            assertFalse(Files.exists(legacy));
            assertArrayEquals(binary, Files.readAllBytes(skill.resolve("guides/opaque.bin")));
            assertEquals("Original guide.\r\n", Files.readString(skill.resolve("guides/text.bin")));
            assertEquals("My edits.", Files.readString(skill.resolve("guides/edited.md")));
            assertEquals("My own guide.", Files.readString(skill.resolve("guides/user.md")));
        }
        assertCli(0, after, workspace, "doctor");
    }

    @Test
    @Tag("cli-compat")
    void doctorIdentifiesARetainedRetiredNativeSkillAlongsidePersonalSkills() throws Exception {
        Path pack = pack("inspect", "add: {skill/camel-inspect: {contract: 1, resource: inspect.md}}");
        write("inspect", "inspect.md", SKILL);
        Path before = distribution(pack, "inspect");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        assertCli(0, before, workspace, "init", "--here", "--ai", "claude", "--no-fetch");
        String retired = ".claude/skills/camel-inspect/SKILL.md";
        Path entry = workspace.resolve(retired);
        Files.writeString(entry, Files.readString(entry) + "Team instructions.");
        Path personal = Files.createDirectories(workspace.resolve(".claude/skills/team")).resolve("SKILL.md");
        Files.writeString(personal, "Our team skill.");
        String init = assertCli(0, temp, workspace, "init", "--here", "--ai", "claude", "--force", "--no-fetch");
        assertTrue(init.contains(retired), init);
        String doctor = assertCli(1, temp, workspace, "doctor");
        assertTrue(doctor.contains(retired), doctor);
        assertTrue(doctor.contains("Retired catalog entry point"), doctor);
        String invalid
                = assertCli(1, temp, workspace, "init", "--here", "--ai", "claude", "--force", "--replace", retired);
        assertTrue(invalid.contains(retired), invalid);
        assertTrue(Files.readString(entry).endsWith("Team instructions."));
        Files.move(entry, workspace.resolve("archived-skill.md"));
        assertCli(0, temp, workspace, "doctor");
        assertEquals("Our team skill.", Files.readString(personal));
    }

    private static void assertPreview(String preview, String action, String path) {
        assertTrue(preview.replaceAll("\u001B\\[[0-9;]*m", "").lines().map(String::strip)
                .anyMatch(line -> List.of(line.split("\\s+", 2)).equals(List.of(action, path))), preview);
    }

    @Test
    @Tag("cli-compat")
    void catalogLayoutChangesKeepTheOldInstallationOnFailure() throws Exception {
        write("flat", "entry.md", SKILL.replace("camel-inspect", "camel-old"));
        write("flat", "shape.txt", "Flat");
        write("flat", "guide.md", "Same content");
        write("nested", "entry.md", SKILL.replace("camel-inspect", "camel-new"));
        write("nested", "shape.txt", "Nested");
        write("nested", "guide.md", "Same content");
        Path flat = distribution(pack("flat", """
                add:
                  skill/camel-old: {contract: 1, resource: entry.md}
                  resource/shared/layout: {contract: 1, resource: shape.txt}
                  resource/shared/Guide.md: {contract: 1, resource: guide.md}
                """), "flat");
        Path nested = distribution(pack("nested", """
                add:
                  skill/camel-new: {contract: 1, resource: entry.md}
                  resource/shared/layout/guide.md: {contract: 1, resource: shape.txt}
                  resource/shared/guide.md: {contract: 1, resource: guide.md}
                """), "nested");
        Path workspace = Files.createDirectory(temp.resolve("layout-workspace"));
        Path skills = workspace.resolve(".bob/skills");
        Path old = skills.resolve("camel-old/SKILL.md");
        Path stub = workspace.resolve(".bob/commands/camel-old.md");
        Path shape = skills.resolve("shared/layout");
        assertCli(0, flat, workspace, "init", "--here", "--ai", "bob2", "--no-fetch");
        Path conflict = Files.writeString(skills.resolve("camel-new"), "My file");
        for (String mode : List.of("--dry-run", "--force")) {
            String output = assertCli(1, nested, workspace, "init", "--here", "--ai", "bob2", mode, "--no-fetch");
            assertTrue(output.contains("camel-new"), output);
            assertEquals("My file", Files.readString(conflict));
            assertTrue(Files.isRegularFile(old));
            assertTrue(Files.isRegularFile(stub));
            assertEquals("Flat", Files.readString(shape));
        }
        Files.delete(conflict);
        Path worker = workspace.resolve(".bob/agents/camel-worker.md");
        Files.delete(worker);
        Files.createDirectory(worker);
        Path note = Files.writeString(worker.resolve("note"), "Keep this directory");
        // Bob writes this template after installing skills, including the staged layout.
        assertCli(1, nested, workspace, "init", "--here", "--ai", "bob2", "--force", "--no-fetch");
        assertEquals("Keep this directory", Files.readString(note));
        assertEquals("Flat", Files.readString(shape));
        assertTrue(Files.isRegularFile(old));
        assertTrue(Files.isRegularFile(stub));
        Files.delete(note);
        Files.delete(worker);
        assertCli(0, nested, workspace, "init", "--here", "--ai", "bob2", "--force", "--no-fetch");
        assertEquals("Nested", Files.readString(shape.resolve("guide.md")));
        assertNoStagingDirectories(skills);
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(stub));
        assertEquals("Same content", Files.readString(skills.resolve("shared/guide.md")));
        if (Files.getFileStore(shape).supportsFileAttributeView("posix")) {
            var originalPermissions = Files.getPosixFilePermissions(shape);
            try {
                Files.setPosixFilePermissions(shape, PosixFilePermissions.fromString("r-xr-xr-x"));
                if (!Files.isWritable(shape)) {
                    for (String mode : List.of("--dry-run", "--force")) {
                        String output
                                = assertCli(1, flat, workspace, "init", "--here", "--ai", "bob2", mode, "--no-fetch");
                        assertDiagnosticPath(output, "directory is not writable: ", workspace.relativize(shape));
                    }
                    assertEquals("Nested", Files.readString(shape.resolve("guide.md")));
                    assertNoStagingDirectories(skills);
                }
            } finally {
                Files.setPosixFilePermissions(shape, originalPermissions);
            }
        }
        Path extra = Files.writeString(shape.resolve("my-guide.md"), "Keep me");
        assertCli(1, flat, workspace, "init", "--here", "--ai", "bob2", "--force", "--no-fetch");
        assertEquals("Keep me", Files.readString(extra));
        assertEquals("Nested", Files.readString(shape.resolve("guide.md")));
        Files.delete(extra);
        assertCli(0, flat, workspace, "init", "--here", "--ai", "bob2", "--force", "--no-fetch");
        assertEquals("Flat", Files.readString(shape));
        assertTrue(Files.isRegularFile(old));
        assertTrue(Files.isRegularFile(stub));
        assertNoStagingDirectories(skills);
    }

    @Test
    @Tag("cli-compat")
    void recoveryChecksDistinguishSkillRootsFromCatalogWorkDirectories() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        assertCli(0, temp, workspace, "init", "--here", "--ai", "claude", "--no-fetch");
        assertCli(0, temp, workspace, "graph", "generate", "--project-dir", workspace.toString());
        Path skills = workspace.resolve(".claude/skills");
        Path personal = Files.createDirectory(skills.resolve(".camel-kit-catalog-1234"));
        Path note = Files.writeString(personal.resolve("previous"), "My notes");
        assertCli(0, temp, workspace, "init", "--here", "--ai", "claude", "--force", "--no-fetch");
        assertCli(0, temp, workspace, "doctor");
        if (Files.getFileStore(skills).supportsFileAttributeView("posix")) {
            Path nested = Files.createDirectory(skills.resolve("shared").resolve(personal.getFileName()));
            for (Path dir : List.of(personal, nested)) {
                var permissions = Files.getPosixFilePermissions(dir);
                Files.setPosixFilePermissions(dir, Set.of());
                try {
                    if (Files.isReadable(dir)) {
                        continue;
                    }
                    int status = dir.equals(nested) ? 1 : 0;
                    for (String[] args : List.of(new String[]{"doctor"},
                            new String[]{"init", "--here", "--ai", "claude", "--force", "--no-fetch"},
                            new String[]{"init", "--here", "--ai", "claude", "--dry-run", "--no-fetch"})) {
                        String output = assertCli(status, temp, workspace, args);
                        if (status == 1) {
                            assertDiagnosticPath(output, "Cannot inspect ", workspace.relativize(nested));
                            assertTrue(output.contains("Restore read and search permissions"), output);
                            assertFalse(output.contains("AccessDeniedException"), output);
                        }
                    }
                } finally {
                    Files.setPosixFilePermissions(dir, permissions);
                }
            }
        }
        assertEquals("My notes", Files.readString(note));
    }

    private static void assertDiagnosticPath(String output, String message, Path relative) {
        // Child processes can resolve the temporary directory through a different spelling on macOS and Windows.
        assertTrue(output.lines().anyMatch(line -> line.contains(message) && line.contains(relative.toString())),
                output);
    }

    private static void assertNoStagingDirectories(Path root) throws IOException {
        try (var entries = Files.walk(root)) {
            assertEquals(List.of(),
                    entries.filter(path -> path.getFileName().toString().startsWith(".camel-kit-catalog-"))
                            .toList());
        }
    }

    @Test
    @Tag("cli-compat")
    void interruptedLayoutUpdateRemainsVisibleUntilItsBackupIsRecovered() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("interrupted"));
        assertCli(0, temp, workspace, "init", "--here", "--ai", "claude", "--no-fetch");
        Path skills = workspace.resolve(".claude/skills");
        Path scratch = skills.resolve("shared/.camel-kit-catalog-1234");
        Path backup = Files.createDirectories(scratch.resolve("previous/index")).resolve("guide.md");
        Files.writeString(backup, "Previous guide");
        for (String mode : List.of("--dry-run", "--force")) {
            String result = assertCli(1, temp, workspace, "init", "--here", "--ai", "claude", mode, "--no-fetch");
            assertDiagnosticPath(result, "Unfinished catalog update at ", workspace.relativize(scratch));
            assertEquals("Previous guide", Files.readString(backup));
        }
        String doctor = assertCli(1, temp, workspace, "doctor");
        assertDiagnosticPath(doctor, "Unfinished catalog update at ", workspace.relativize(scratch));
        Files.move(scratch, temp.resolve("recovered"));
        assertCli(0, temp, workspace, "init", "--here", "--ai", "claude", "--force", "--no-fetch");
        assertCli(0, temp, workspace, "doctor");
        assertNoStagingDirectories(skills);
    }

    @Test
    void writeFailureIdentifiesTheCatalogAndFilesystemReason() throws Exception {
        Path output = Files.createFile(temp.resolve("file")).resolve("catalog.zip");
        var failure = assertThrows(IOException.class, () -> SkillPackCompiler.writeCatalog(output, new byte[0]));
        assertTrue(failure.getMessage().contains("Cannot write skill catalog " + output), failure::getMessage);
        assertTrue(failure.getMessage().contains(failure.getCause().getClass().getSimpleName()), failure::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shared/IRON-LAWS.md", "shared/iron-laws.md/nested.md"})
    void catalogLoaderRejectsConflictingInstalledPaths(String extraPath) throws Exception {
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        var catalog = SkillCatalog.bundled();
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(SkillCatalog.MANIFEST));
            zip.write(SkillCatalog.JSON.writeValueAsBytes(catalog.manifest()));
            for (String path : catalog.paths()) {
                zip.putNextEntry(new ZipEntry("skills/" + path));
                catalog.open(path).transferTo(zip);
            }
            zip.putNextEntry(new ZipEntry("skills/" + extraPath));
        }
        try (var loader = loader(temp)) {
            var error = assertThrows(IOException.class, () -> SkillCatalog.load(loader));
            String reason = extraPath.endsWith("nested.md") ? "both file and directory" : "differ only by case";
            assertTrue(error.getMessage().contains(reason), error::getMessage);
            assertTrue(error.getMessage().contains("shared/iron-laws.md"), error::getMessage);
        }
    }

    @Test
    void catalogLoaderRejectsAnUnsupportedContract() throws Exception {
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        var original = SkillCatalog.bundled().manifest();
        var future = new SkillCatalog.Manifest(2, original.packs(), original.workflow(), original.contributions());
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(SkillCatalog.MANIFEST));
            zip.write(SkillCatalog.JSON.writeValueAsBytes(future));
        }
        try (var loader = loader(temp)) {
            assertEquals("Unsupported skill catalog contract: 2",
                    assertThrows(IOException.class, () -> SkillCatalog.load(loader)).getMessage());
        }
    }

    @Test
    void catalogLoaderRejectsTwoAssembledArchives() throws Exception {
        Path one = temp.resolve("module one");
        Path two = temp.resolve("module two");
        for (Path module : List.of(one, two)) {
            Path archive = module.resolve(SkillCatalog.DISTRIBUTION);
            Files.createDirectories(archive.getParent());
            Files.createFile(archive);
        }
        try (var loader = loader(one, two)) {
            var error = assertThrows(IOException.class, () -> SkillCatalog.load(loader));
            assertTrue(error.getMessage().startsWith("Expected one assembled Camel Kit skill catalog"),
                    error::getMessage);
            for (Path module : List.of(one, two)) {
                assertTrue(
                        error.getMessage()
                                .contains(module.resolve(SkillCatalog.DISTRIBUTION).toUri().toURL().toString()),
                        error::getMessage);
            }
        }
    }

    @Nested
    class ComposedDistribution {
        Path pack;
        Path overlay;
        Path exampleJar;
        String skill;
        String guide;
        String provider;
        byte[] first;
        Path distribution;

        @BeforeEach
        void assemble() throws Exception {
            Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources").toRealPath();
            skill = "  skill/camel-inspect: {contract: 1, resource: SKILL.md}\n";
            guide = "  resource/camel-inspect/guides/inspect.md: {contract: 1, resource: guide.md}\n";
            provider
                    = "  provider/list: {contract: 1, provider: {service: java.util.List, implementation: java.util.ArrayList}}\n";
            pack = pack("inspect", "add:\n" + skill + guide + provider);
            write("inspect", "SKILL.md", SKILL);
            write("inspect", "guide.md", "Keep this guide.");
            overlay = pack("team",
                    """
                            requires: [inspect, core]
                            replace:
                              skill/camel-inspect: {contract: 1, resource: SKILL.md}
                              provider/list: {contract: 1, provider: {service: java.util.List, implementation: java.util.LinkedList}}
                            exclude: [step/validate/report/timestamp]
                            """);
            write("team", "SKILL.md", SKILL.replace("Original", "Team"));
            exampleJar = temp.resolve("report-review.jar");
            try (var zip = new ZipOutputStream(Files.newOutputStream(exampleJar)); var files = Files.walk(example)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new ZipEntry(example.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
            try (var loader = loader(exampleJar, pack, overlay)) {
                first = SkillPackCompiler.compile(loader, List.of("team", "report-review", "inspect"));
            }
            distribution = temp.resolve("distribution");
            Path archive = distribution.resolve(SkillCatalog.DISTRIBUTION);
            Files.createDirectories(archive.getParent());
            Files.write(archive, first);
        }

        @Test
        void independentModuleAndDeclarationOrderProduceIdenticalCatalogs() throws Exception {
            pack("inspect", "add:\n" + provider + guide + skill);
            try (var loader = loader(overlay, pack, exampleJar)) {
                assertArrayEquals(first,
                        SkillPackCompiler.compile(loader, List.of("inspect", "team", "report-review")));
            }
        }

        @Test
        void archiveContainsTheSelectedProviderAndStepInstructions() throws Exception {
            // Consume the assembled distribution without any input pack directories or JARs on the runtime classpath.
            try (var loader = loader(distribution)) {
                SkillCatalog catalog = SkillCatalog.load(loader);
                assertEquals("java.util.LinkedList",
                        catalog.manifest().contributions().get("provider/list").provider().implementation());
                String validate = content(catalog, "camel-validate/SKILL.md");
                assertTrue(validate.indexOf("## Validation Report\n") < validate.indexOf(REVIEW));
                assertTrue(validate.indexOf(REVIEW) < validate.indexOf("### Report Format"));
                assertFalse(validate.contains("Use the current date and time for the timestamped filename"));
            }
        }

        @Test
        void installedFilesMatchTheCatalogAndDoctorNamesAMissingGuide() throws Exception {
            Path workspace = Files.createDirectory(temp.resolve("workspace"));
            assertCli(0, distribution, workspace, "init", "--here", "--ai", "bob2", "--no-fetch");
            Path skills = workspace.resolve(".bob/skills");
            assertTrue(Files.readString(skills.resolve("camel-validate/SKILL.md")).contains(REVIEW));
            assertTrue(Files.readString(skills.resolve("camel-inspect/SKILL.md")).contains("Team instructions."));
            assertTrue(Files.readString(skills.resolve("camel-start/SKILL.md"))
                    .contains("/camel-inspect — Inspect an integration."));
            assertTrue(Files.readString(workspace.resolve(".bob/commands/camel-inspect.md"))
                    .contains(".bob/skills/camel-inspect/SKILL.md"));
            Path installedGuide = skills.resolve("camel-inspect/guides/inspect.md");
            assertEquals("Keep this guide.", Files.readString(installedGuide));
            assertCli(0, distribution, workspace, "doctor");
            Files.delete(installedGuide);
            assertTrue(assertCli(1, distribution, workspace, "doctor").contains("camel-inspect/guides/inspect.md"));
        }
    }

    @Test
    void installsInternalSkillWithFlowStyleFrontmatterAndExampleMetadataInItsBody() throws Exception {
        Path source = pack("library", "add: {skill/camel-reader: {contract: 1, resource: reader.md}}");
        String description
                = "Read the route and list the affected integrations before proposing changes to its configuration.";
        write("library", "reader.md", """
                ---
                {name: camel-reader, description: DESCRIPTION, user_invocable: false}
                ---

                Example settings from an input project:
                ```yaml
                user-invocable: true
                disable-model-invocation: false
                ```
                """.replace("DESCRIPTION", description));
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(source)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("library")));
        }
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        assertCli(0, temp, workspace, "init", "--here", "--ai", "claude", "--no-fetch");
        String installed = Files.readString(workspace.resolve(".claude/skills/camel-reader/SKILL.md"));
        String yaml = FrontmatterHandler.extractFrontmatterYaml(installed);
        var header = SkillPack.YAML.readTree(yaml);
        assertEquals(description, header.get("description").textValue());
        assertTrue(yaml.contains(description));
        assertFalse(header.get("user-invocable").booleanValue());
        assertTrue(header.get("disable-model-invocation").booleanValue());
        assertTrue(installed.contains("```yaml\nuser-invocable: true\ndisable-model-invocation: false\n```"));
    }

    @Test
    void excludingAPackSkillRemovesItsCommandRouteAndOwnedGuides() throws Exception {
        Path pack = pack("inspect", """
                add:
                  skill/camel-inspect: {contract: 1, resource: SKILL.md}
                  resource/camel-inspect/guides/extra.md: {contract: 1, resource: extra.md}
                """);
        write("inspect", "SKILL.md", SKILL);
        write("inspect", "extra.md", "Extra.");
        Path overlay = pack("small", "requires: [inspect]\nexclude: [skill/camel-inspect]\n");
        Path out = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(out.getParent());
        try (var loader = loader(pack, overlay)) {
            Files.write(out, SkillPackCompiler.compile(loader, List.of("small", "inspect")));
        }
        try (var loader = loader(temp)) {
            var catalog = SkillCatalog.load(loader);
            assertFalse(catalog.paths().stream().anyMatch(s -> s.startsWith("camel-inspect/")));
            assertFalse(
                    catalog.manifest().workflow().commands().stream().anyMatch(c -> c.name().equals("camel-inspect")));
            assertFalse(content(catalog, "camel-start/SKILL.md").contains("/camel-inspect"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "requires: [absent]|Missing pack dependency: bad -> absent",
            "requires: [bad]|Cyclic pack dependency: bad -> bad",
            "add: {skill/camel-start: {contract: 1, resource: start.md}}|core: skill/camel-start",
            "add: {skill/shared: {contract: 1, resource: guide.md}}|bad: skill/shared: reserved skill name",
            "add: {step/validate/report/id: {contract: 2, resource: guide.md}}|unsupported contribution contract 2",
            "add: {step/validate/report/id: {contract: 1.5, resource: guide.md}}|1.5",
            "add: {step/validate/missing/id: {contract: 1, resource: guide.md}}|validate/missing",
            "add: {step/missing/report/id: {contract: 1, resource: guide.md}}|unknown workflow stage missing",
            "add: {step/validate/report/id: {contract: 1, resource: missing.md}}|missing.md",
            "add: {resource/absent/guide.md: {contract: 1, resource: guide.md}}|requires effective skill/absent",
            "add: {provider/check: {contract: 1, provider: {service: java.lang.Runnable, implementation: no.SuchClass}}}|no.SuchClass",
            "add: {provider/check: {contract: 1, provider: {service: java.lang.Runnable, implementation: java.util.ArrayList}}}|implementation of java.lang.Runnable",
            "exclude: [skill/unknown]|no such contribution",
            "exclude: [skill/camel-start]|requires must name",
            "requires: [core]\nexclude: [skill/camel-start]|bad: skill/camel-start: required by core workflow stage route",
            "add: {step/validate/report/id: {contract: 1, resource: guide.md, requires: [provider/missing]}}|requires missing contribution provider/missing",
            "unknown: true|unknown",
            "exclude: []\nexclude: []|Duplicate"
    })
    void malformedDeclarationsIdentifyTheFailure(String scenario) throws Exception {
        String[] parts = scenario.split("\\|");
        Path pack = pack("bad", parts[0]);
        write("bad", "guide.md", "Additional guidance.");
        write("bad", "start.md", SKILL.replace("camel-inspect", "camel-start"));
        try (var loader = loader(pack)) {
            var failure = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("bad")));
            assertTrue(failure.getMessage().contains(parts[1]), failure::getMessage);
        }
    }

    @Test
    void rejectsCompetingReplacementsAndAmbiguousResourceOrigins() throws Exception {
        String replace = """
                requires: [core]
                replace:
                  step/validate/report/timestamp: {contract: 1, resource: guide.md}
                """;
        Path one = pack("one", replace);
        Path two = pack("two", replace);
        write("one", "guide.md", "One.");
        write("two", "guide.md", "Two.");
        try (var loader = loader(two, one)) {
            IOException failure
                    = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("two", "one")));
            assertTrue(failure.getMessage().contains("two: step/validate/report/timestamp: conflicts with one:"),
                    failure::getMessage);
        }
        Path other = temp.resolve("other");
        Path duplicate = other.resolve(SkillPack.directory("one")).resolve("guide.md");
        Files.createDirectories(duplicate.getParent());
        Files.writeString(duplicate, "Ambiguous.");
        try (var loader = loader(one, other)) {
            IOException failure
                    = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("one")));
            assertTrue(failure.getMessage().contains("one: step/validate/report/timestamp: Expected one resource"),
                    failure::getMessage);
        }
    }

    @Test
    void rejectsDuplicatePackIdsUnsupportedSchemasAndIncompatibleProviderReplacements() throws Exception {
        Path source = pack("upstream",
                "add: {provider/check: {contract: 1, provider: {service: java.util.List, implementation: java.util.ArrayList}}}");
        Path overlay = pack("team", """
                requires: [upstream]
                replace:
                  provider/check: {contract: 1, provider: {service: java.util.Set, implementation: java.util.HashSet}}
                """);
        try (var loader = loader(source, overlay)) {
            var duplicate = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("upstream", "upstream")));
            assertTrue(duplicate.getMessage().contains("duplicate selected pack ID: upstream"), duplicate::getMessage);
            var service = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("upstream", "team")));
            assertTrue(service.getMessage().contains(
                    "team: provider/check: replacement must preserve service java.util.List declared by upstream:"),
                    service::getMessage);
            write("team", "pack.yaml", "id: team\nschema: 9\n");
            var schema = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("team")));
            assertTrue(schema.getMessage().contains("'team' schema 9"), schema::getMessage);
        }
    }

    @Test
    void migrationPackParsesAlongsideMuleAndReportsUnavailableRuntimeClass() throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main").toRealPath();
        Path dist = Files.createDirectory(temp.resolve("distribution"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path"), "-d", dist.toString(),
                example.resolve("java/example/LegacyFlowParser.java").toString()));
        Path archive = dist.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(dist, example.resolve("resources"))) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("legacy-flows")));
        }

        Path project = Files.createDirectory(temp.resolve("source"));
        Path graph = project.resolve(".camel-kit/project-graph.json");
        Files.writeString(project.resolve("orders.flow"), "Orders");
        Files.writeString(project.resolve("empty.flow"), "");
        Files.writeString(project.resolve("ignored.txt"), "unrelated");
        String mule = "<mule xmlns='http://www.mulesoft.org/schema/mule/core'>";
        Files.writeString(project.resolve("mule.xml"), mule + "<flow name='orders'/></mule>");
        Files.writeString(project.resolve("broken.xml"), mule);
        String output = assertCli(0, dist, project, "graph", "generate", "--require-parser", "legacy-flow");
        assertTrue(output.contains("Missing flow name in empty.flow"), output);
        assertTrue(output.contains("Could not parse Mule XML file broken.xml"), output);
        var result = GraphSerializer.read(graph);
        assertEquals(Map.of("name", "Orders", "file", "orders.flow"),
                result.getNode("legacy-flow:orders.flow").properties());
        assertEquals("mule.xml", result.getNode("mule-flow:orders").properties().get("file"));
        assertFalse(result.hasNode("legacy-flow:ignored.txt"));

        byte[] saved = Files.readAllBytes(graph);
        try (var file = new java.io.RandomAccessFile(project.resolve("large.flow").toFile(), "rw")) {
            file.setLength(GraphParser.MAX_SOURCE_BYTES + 1L);
        }
        String oversized = assertCli(1, dist, project, "graph", "generate");
        assertTrue(oversized.contains("Graph input exceeds") && oversized.contains("large.flow"), oversized);
        assertArrayEquals(saved, Files.readAllBytes(graph));
        Files.delete(project.resolve("large.flow"));
        Files.delete(dist.resolve("example/LegacyFlowParser.class"));
        String missing = assertCli(1, dist, project, "graph", "generate", "--require-parser", "legacy-flow");
        assertTrue(missing.contains("provider/legacy-flow") && missing.contains("example.LegacyFlowParser"), missing);
        assertArrayEquals(saved, Files.readAllBytes(graph));
    }

    @Test
    void excludedParserIsAbsentEvenWhenItsClassIsPackaged() throws Exception {
        Path dist
                = distribution(pack("without-mule", "requires: [core]\nexclude: [provider/mule-xml]"), "without-mule");
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("mule.xml"),
                "<mule xmlns='http://www.mulesoft.org/schema/mule/core'><flow name='orders'/></mule>");
        Path graph = source.resolve(".camel-kit/project-graph.json");
        assertCli(0, dist, source, "graph", "generate");
        assertFalse(GraphSerializer.read(graph).hasNode("mule-flow:orders"));
        byte[] saved = Files.readAllBytes(graph);
        String missing = assertCli(1, dist, source, "graph", "generate", "--require-parser", "mule-xml");
        assertTrue(missing.contains("Required migration parser provider/mule-xml is unavailable"), missing);
        assertArrayEquals(saved, Files.readAllBytes(graph));
    }

    public static class FutureParser extends MuleXmlFlowParser {
        @Override
        public int apiVersion() {
            return 2;
        }
    }

    @Test
    void migrationParserIdentityAndApiCompatibilityAreChecked() throws Exception {
        String provider = String.format(Locale.ROOT, """
                  provider/mule-xml:
                    contract: 1
                    provider:
                      service: org.apache.camel.kit.graph.parser.MigrationParser
                      implementation: %s
                """, FutureParser.class.getName());
        Path duplicate = pack("other", "add:\n" + provider);
        try (var loader = loader(duplicate)) {
            var failure = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("other")));
            assertTrue(failure.getMessage().contains("provider/mule-xml")
                    && failure.getMessage().contains("conflicts with"), failure::getMessage);
        }
        Path dist = distribution(pack("future", "requires: [core]\nreplace:\n" + provider), "future");
        String output = assertCli(1, dist, temp, "graph", "generate", "--require-parser", "mule-xml");
        assertTrue(output.contains("provider/mule-xml requires API 2, supported: 1"), output);
        assertFalse(Files.exists(temp.resolve(".camel-kit/project-graph.json")));
    }

    private static final String COVERAGE = "step/validate/report/coverage";
    private static final String HOOK_INPUT = """
            {"sources":["orders.yaml"],"target":"quarkus","artifacts":{"inventory":"coverage.tsv"}}
            """;

    @Test
    @Tag("cli-compat")
    void installedSkillSubmitsItsDecisionFailureAndVerifiedCoverage() throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources").toRealPath();
        Path dist = distribution(example, "report-review");
        assertCli(0, dist, temp, "init", "--here", "--ai", "bob2", "--no-fetch", "--silent");
        Path skills = temp.resolve(".bob/skills");
        assertTrue(Files.readString(skills.resolve("camel-validate/SKILL.md")).contains("hook --id " + COVERAGE));
        Files.writeString(temp.resolve("orders.yaml"), "route: orders");
        Path inventory = Files.writeString(temp.resolve("coverage.tsv"), "unit\torders.yaml\n");
        Path input = Files.writeString(temp.resolve("input.json"), HOOK_INPUT);
        var pending = submitCoverage(dist, skills, 1, false);
        assertEquals("needs-input", pending.path("status").asText());
        Files.writeString(temp.resolve("pending.json"), pending.toString());
        String confirmed = HOOK_INPUT.replace("\"sources\"", "\"answer\":\"integration\",\"sources\"");
        Files.writeString(input, confirmed);
        var failure = submitCoverage(dist, skills, 1, true);
        assertEquals("failed", failure.path("status").asText());
        assertTrue(failure.at("/checks/route-coverage/evidence").asText().contains("missing orders.yaml"));
        Files.writeString(inventory, "integration\torders.yaml\n");
        String staleDecision = assertCli(1, dist, temp, "hook", "--id", COVERAGE, "--input", "input.json", "--resume",
                "pending.json");
        assertTrue(staleDecision.contains("artifacts"), staleDecision);
        Files.writeString(input, HOOK_INPUT);
        Files.writeString(temp.resolve("pending.json"), submitCoverage(dist, skills, 1, false).toString());
        Files.writeString(input, confirmed);
        assertEquals("passed", submitCoverage(dist, skills, 0, true).path("status").asText());
        Files.writeString(inventory, "");
        String stale = assertCli(1, dist, temp, "hook", "--id", COVERAGE,
                "--input", "input.json", "--result", "result.json", "--resume", "pending.json");
        assertTrue(stale.contains("artifacts"), stale);
    }

    private JsonNode submitCoverage(Path dist, Path skills, int expectedExit, boolean resume) throws Exception {
        var args = new ArrayList<>(List.of("hook", "--id", COVERAGE, "--input", "input.json"));
        if (resume) {
            args.addAll(List.of("--resume", "pending.json"));
        }
        String dispatch = assertCli(1, dist, temp, args.toArray(String[]::new));
        var message = SkillCatalog.JSON.readTree(dispatch);
        assertEquals("invoke-skill", message.path("status").asText());
        var request = message.required("request");
        Path entry = skills.resolve(request.required("implementation").textValue());
        assertTrue(Files.readString(entry).contains("guides/coverage.py"));
        Files.writeString(temp.resolve("request.json"), request.toString());
        String python = File.separatorChar == '\\' ? "python" : "python3";
        var process = new ProcessBuilder(python, entry.resolveSibling("guides/coverage.py").toString(), "request.json")
                .directory(temp.toFile()).redirectOutput(temp.resolve("result.json").toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertEquals(0, process.waitFor());
        args.addAll(List.of("--result", "result.json"));
        return SkillCatalog.JSON.readTree(assertCli(expectedExit, dist, temp, args.toArray(String[]::new)));
    }

    @ParameterizedTest
    @CsvSource({
            "resource/shared/guide.md, expected a named step and a skill/<id> or provider/<id> handler",
            "skill/camel-migrate, unsupported hook handler",
            "skill/missing, requires missing contribution",
            "provider/list, unsupported hook handler"
    })
    void buildRejectsUnsupportedHookHandlers(String handler, String reason) throws Exception {
        Path pack = pack("bad", String.format(Locale.ROOT, """
                add:
                  provider/list:
                    contract: 1
                    provider: {service: java.util.List, implementation: java.util.ArrayList}
                  step/validate/report/check:
                    contract: 1
                    hook: {handler: %s, targets: [quarkus], checks: {coverage: report}}
                """, handler));
        try (var loader = loader(pack)) {
            var error = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("bad")));
            assertTrue(error.getMessage().contains("step/validate/report/check"), error::getMessage);
            assertTrue(error.getMessage().contains(reason), error::getMessage);
            assertTrue(error.getMessage().contains(handler), error::getMessage);
        }
    }

    @Test
    void exclusionRemovesTheHookAndItsInstructionsButKeepsTheHandler() throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources");
        Path overlay = pack("overlay", "requires: [report-review]\nexclude: [" + COVERAGE + "]");
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(example, overlay)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("report-review", "overlay")));
        }
        try (var loader = loader(temp)) {
            var catalog = SkillCatalog.load(loader);
            var entries = catalog.manifest().contributions();
            assertFalse(entries.containsKey(COVERAGE));
            assertEquals(List.of(COVERAGE), entries.get("skill/camel-review-coverage").handles());
            String instructions = content(catalog, "camel-validate/SKILL.md");
            assertFalse(instructions.contains(COVERAGE));
            assertFalse(instructions.contains(WorkflowHook.PROTOCOL));
            assertTrue(instructions.contains(REVIEW));
        }
    }

    @Test
    void sharedProtocolImmediatelyFollowsTheLastHookAtThePoint() throws Exception {
        String second = "step/validate/report/second";
        Path addon = pack("more", """
                requires: [report-review]
                add:
                  skill/camel-inspect:
                    contract: 1
                    resource: inspect.md
                    handles: [step/validate/report/second]
                  step/validate/report/second:
                    contract: 1
                    hook: {handler: skill/camel-inspect, targets: [quarkus], checks: {smoke: report}}
                """);
        write("more", "inspect.md", SKILL);
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources");
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(example, addon)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("report-review", "more")));
        }
        try (var loader = loader(temp)) {
            String stage = content(SkillCatalog.load(loader), "camel-validate/SKILL.md");
            var paragraphs = List.of(stage.split("\n\n"));
            int protocol = paragraphs.indexOf(WorkflowHook.PROTOCOL);
            assertTrue(protocol >= 3 && protocol + 1 < paragraphs.size(), "Missing protocol or surrounding steps");
            assertTrue(paragraphs.get(protocol - 3).contains("hook --id " + COVERAGE));
            assertTrue(paragraphs.get(protocol - 2).startsWith(REVIEW));
            assertTrue(paragraphs.get(protocol - 1).contains("hook --id " + second));
            assertTrue(paragraphs.get(protocol + 1).startsWith("Use the current date and time"));
            String pathHelp = "open `request.implementation` relative to the host's installed skills directory";
            assertTrue(stage.contains(pathHelp));
            assertEquals(stage.indexOf(pathHelp), stage.lastIndexOf(pathHelp));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "skill/camel-inspect | step/validate/report/missing | undeclared hook",
            "skill/camel-inspect | step/validate/report/coverage, step/validate/report/coverage | invalid handles",
            "skill/camel-inspect | invalid | undeclared hook",
            "resource/shared/extra.md | step/validate/report/coverage | invalid handles"
    })
    void rejectsInvalidHandlerDeclarations(String owner, String handles, String reason) throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources");
        Path extra = pack("extra", "add: {" + owner + ": {contract: 1, resource: extra.md, handles: ["
                                   + handles + "]}}");
        write("extra", "extra.md", SKILL);
        try (var loader = loader(example, extra)) {
            var error = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("extra", "report-review")));
            assertTrue(error.getMessage().contains(owner), error::getMessage);
            assertTrue(error.getMessage().contains(reason), error::getMessage);
        }
    }

    @Test
    void replacementCannotDropARequiredCheck() throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources").toRealPath();
        Path pack = pack("overlay", """
                requires: [report-review]
                replace:
                  step/validate/report/coverage:
                    contract: 1
                    hook: {handler: skill/camel-validate, targets: [quarkus, spring-boot], checks: {other: inventory}}
                """);
        try (var loader = loader(pack, example)) {
            var error = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("report-review", "overlay")));
            assertTrue(error.getMessage().contains("preserve hook targets and checks"), error::getMessage);
        }
    }

    public static class InventoryProvider implements WorkflowHookProvider {
        @Override
        public Result invoke(Path project, Request request) throws IOException {
            if (request.decisions().isEmpty()) {
                return new Result(request, "needs-input", Map.of(), "Which suite?");
            }
            String answer = request.decisions().getLast().answer();
            var inventory = project.resolve(request.artifacts().get("inventory").path());
            if (answer.equals("rewrite")) {
                Files.writeString(inventory, "changed during dispatch");
            }
            boolean passed = Files.readAllLines(inventory).contains(answer);
            return new Result(
                    request, passed ? "passed" : "failed",
                    Map.of("route-coverage", new Check(passed, "Looked for suite " + answer)), null);
        }
    }

    @Test
    void executableReplacementDispatchesJavaAndRechecksItsFiles() throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources").toRealPath();
        Path overlay = pack("compiled", String.format(Locale.ROOT, """
                requires: [report-review]
                add:
                  provider/inventory:
                    contract: 1
                    handles: [step/validate/report/coverage]
                    provider: {service: %s, implementation: %s}
                replace:
                  step/validate/report/coverage:
                    contract: 1
                    hook:
                      handler: provider/inventory
                      targets: [spring-boot, quarkus]
                      checks: {route-coverage: inventory}
                """, WorkflowHookProvider.class.getName(), InventoryProvider.class.getName()));
        var archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(example, overlay)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("compiled", "report-review")));
        }
        Files.writeString(temp.resolve("orders.yaml"), "orders");
        Files.writeString(temp.resolve("coverage.tsv"), "integration\n");
        Path input = Files.writeString(temp.resolve("input.json"), HOOK_INPUT);
        String[] invoke = {"hook", "--id", COVERAGE, "--input", "input.json"};
        String pending = assertCli(1, temp, temp, invoke);
        assertEquals("needs-input", SkillCatalog.JSON.readTree(pending).path("status").asText());
        Files.writeString(temp.resolve("pending.json"), pending);
        for (String answer : List.of("unit", "integration", "rewrite")) {
            Files.writeString(input, HOOK_INPUT.replace("\"sources\"", "\"answer\":\"" + answer + "\",\"sources\""));
            String output = assertCli(answer.equals("integration") ? 0 : 1, temp, temp, "hook", "--id", COVERAGE,
                    "--input", "input.json", "--resume", "pending.json");
            if (answer.equals("rewrite")) {
                assertTrue(output.contains("artifacts"), output);
            } else {
                var result = SkillCatalog.JSON.readTree(output);
                assertEquals(answer.equals("integration") ? "passed" : "failed", result.path("status").asText());
                assertEquals("provider/inventory", result.at("/request/handler").asText());
            }
        }
        assertTrue(
                assertCli(1, temp, temp, "hook", "--id", COVERAGE, "--input", "input.json", "--result", "forged.json")
                        .contains("requires Java dispatch"));
    }

    private Path pack(String id, String yaml) throws IOException {
        write(id, "pack.yaml", "id: " + id + "\nschema: 1\n" + yaml + "\n");
        return temp.resolve(id);
    }

    private void write(String id, String file, String text) throws IOException {
        Path path = temp.resolve(id).resolve(SkillPack.directory(id)).resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static URLClassLoader loader(Path... modules) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Path module : modules) {
            urls.add(module.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), SkillPackCompilerTest.class.getClassLoader());
    }

    private static String content(SkillCatalog catalog, String file) throws IOException {
        return new String(catalog.open(file).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String assertCli(int status, Path distribution, Path cwd, String... args) throws Exception {
        return assertJava(status, distribution, cwd, CamelKitMain.class.getName(), args);
    }

    private static String assertJava(int status, Path distribution, Path cwd, String main, String... args)
            throws Exception {
        String classpath = distribution + File.pathSeparator + System.getProperty("java.class.path");
        List<String> command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classpath, main));
        command.addAll(List.of(args));
        // Catalog tests exercise init and Doctor without invoking external Camel or JBang installations.
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(status, process.waitFor(), output);
        return output;
    }
}
