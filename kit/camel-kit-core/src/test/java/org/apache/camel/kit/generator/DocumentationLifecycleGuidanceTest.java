package org.apache.camel.kit.generator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.camel.kit.command.doc.DocCommand;
import org.apache.camel.kit.config.AgentConfig;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.doc.DocumentationEvidence;
import org.apache.camel.kit.output.Printer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class DocumentationLifecycleGuidanceTest {

    @TempDir
    Path tempDir;

    static Stream<String> agents() {
        return AgentRegistry.names().stream().sorted();
    }

    @ParameterizedTest
    @MethodSource("agents")
    void installedReviewCommandsWorkAndReinitPreservesProjectArtifacts(String agentName) throws Exception {
        for (String prefix : List.of("camel-kit", "camel kit")) {
            Path project = tempDir.resolve(agentName + (prefix.equals("camel-kit") ? "-cli" : "-plugin"));
            AgentConfig agent = AgentRegistry.get(agentName);
            Path skills = project.resolve(agent.skillsDirectory());
            Path commands = agent.generatesCommandStubs() ? project.resolve(agent.commandDirectory()) : skills;
            InitContext context = new InitContext(agent, agentName, commands, skills, project, prefix, Printer.noop());
            Path document = write(project, "docs/camel-kit/system-design.md", "Current description; user's edits.\n");
            write(project, "src/route.yaml", "- from: direct:start\n");
            write(project, "pom.xml", "<project/>\n");
            write(project, "docs/adr/0042-original.md",
                    "Status: accepted\nOriginal neutral context and consequences.\n");
            write(project, "architecture/decisions/0087-custom.md",
                    "Existing project convention and user rationale.\n");
            write(project, "docs/camel-kit/002-existing/design-spec.md", "Approved historical scope.\n");
            write(project, ".camel-kit/pipeline.json", "{\"activePipeline\":\"002-existing\",\"mode\":\"manual\"}\n");
            write(project, "docs/camel-kit/documentation-review.md",
                    "Inspected source and doc; fixture review, no runtime claim.\n");

            AgentGeneratorFactory.create(agentName).generate(context);
            assertTrue(Files.readString(project.resolve("AGENTS.md"))
                    .contains("## Current Documentation and ADRs"), agentName + ": entry guidance");
            assertTrue(Files.readString(project.resolve("AGENTS.md")).contains(
                    project.relativize(skills).toString().replace('\\', '/') + "/shared/documentation-lifecycle.md"),
                    agentName + ": installed entry guide path");
            String invocation = switch (agentName) {
                case "codex" -> "$camel-start";
                case "pi" -> "/skill:camel-start";
                default -> "/camel-start";
            };
            assertTrue(Files.readString(project.resolve("AGENTS.md"))
                    .contains(invocation + " reconcile documentation"), agentName + ": native entry invocation");
            assertFalse(Files.readString(skills.resolve("camel-ship/SKILL.md"))
                    .contains("{SKILLS_DIR}"), agentName + ": unresolved Ship placeholder");
            String guide = Files.readString(skills.resolve("shared/documentation-reconciliation.md"));
            assertFalse(guide.contains("{COMMAND_PREFIX}"));
            assertTrue(Files.isRegularFile(skills.resolve("shared/adr-template.md")));
            for (String consumer : List.of("camel-start", "camel-brainstorm", "camel-migrate", "camel-plan",
                    "camel-execute", "camel-validate", "camel-debug", "camel-ship")) {
                assertTrue(Files.readString(skills.resolve(consumer + "/SKILL.md"))
                        .contains("shared/documentation-lifecycle.md"), agentName + ": " + consumer);
            }

            // Execute the actual rendered command examples through the CLI, not a copy of their options.
            for (String line : guide.lines().map(String::strip).filter(s -> s.startsWith(prefix + " doc ")).toList()) {
                String command = line.substring((prefix + " doc ").length());
                String[] redirect = command.split(" > ", 2);
                List<String> arguments = new ArrayList<>(List.of(redirect[0].split(" +")));
                int root = arguments.indexOf("--project");
                arguments.set(root + 1, project.toString());
                var output = new StringWriter();
                var error = new StringWriter();
                var cli = new CommandLine(new DocCommand());
                cli.setOut(new PrintWriter(output));
                cli.setErr(new PrintWriter(error));
                assertEquals(0, cli.execute(arguments.toArray(String[]::new)), error.toString());
                if (redirect.length == 2) {
                    write(project, redirect[1], output.toString());
                }
                if (arguments.get(0).equals("check")) {
                    assertEquals("unchanged", new ObjectMapper().readTree(output.toString())
                            .path("reconciliation").path("status").asText());
                }
            }
            assertTrue(Files.isRegularFile(Path.of(document + ".reconciliation.json")));
            Map<String, byte[]> original = new java.util.HashMap<>();
            for (String path : List.of("docs/camel-kit/system-design.md",
                    "docs/camel-kit/system-design.md.reconciliation.json",
                    "docs/camel-kit/documentation-review.md", "docs/adr/0042-original.md",
                    "architecture/decisions/0087-custom.md", "docs/camel-kit/002-existing/design-spec.md",
                    ".camel-kit/pipeline.json")) {
                original.put(path, Files.readAllBytes(project.resolve(path)));
            }
            AgentGeneratorFactory.create(agentName).generate(context);
            for (var entry : original.entrySet()) {
                assertArrayEquals(entry.getValue(), Files.readAllBytes(project.resolve(entry.getKey())),
                        entry.getKey());
            }
            write(project, "src/manual.yaml", "- log: '${body}'\n");
            assertEquals("stale", DocumentationEvidence.check(project, document).status());
        }
    }

    private static Path write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
