package org.apache.camel.kit.generator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.camel.Processor;
import org.apache.camel.kit.command.doc.DocCommand;
import org.apache.camel.kit.config.AgentConfig;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.doc.DocumentationEvidence;
import org.apache.camel.kit.output.Printer;
import org.apache.camel.main.Main;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

            new DefaultGenerator().generate(context);
            String guide = Files.readString(skills.resolve("shared/documentation-lifecycle.md"));
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
            new DefaultGenerator().generate(context);
            for (var entry : original.entrySet()) {
                assertArrayEquals(entry.getValue(), Files.readAllBytes(project.resolve(entry.getKey())),
                        entry.getKey());
            }
            write(project, "src/manual.yaml", "- log: '${body}'\n");
            assertEquals("stale", DocumentationEvidence.check(project, document).status());
        }
    }

    @Test
    void observedEvolutionSeparatesArchivedPayloadsFromLogMetadata() throws Exception {
        Path producer = write(tempDir, "src/producer.camel.yaml", """
                - from:
                    uri: direct:incoming
                    steps:
                      - to: direct:consumer
                """);
        Path consumer = write(tempDir, "src/consumer.camel.yaml", consumer(false));
        var logs = new ArrayList<String>();
        var archive = new ArrayList<String>();
        exercise(producer, consumer, logs, archive);
        assertEquals(List.of("event-42"), logs);
        assertTrue(archive.isEmpty());
        Path doc = write(tempDir, "docs/current.md",
                "Producer forwards to consumer; event IDs logged; no payload storage.\n");
        Path evidence
                = write(tempDir, "docs/review.md", "Executed event-42 with sensitive payload; no archive writes.\n");
        Path snapshot = write(tempDir, "inputs.json", DocumentationEvidence.json(
                DocumentationEvidence.snapshot(tempDir, List.of("src"))));
        DocumentationEvidence.record(tempDir, doc, snapshot, evidence, "complete");

        Files.writeString(consumer, consumer(true));
        assertEquals("stale", DocumentationEvidence.check(tempDir, doc).status());
        logs.clear();
        exercise(producer, consumer, logs, archive);
        assertEquals(List.of("event-42"), logs);
        assertEquals(List.of("sensitive-payload"), archive);
        Files.writeString(snapshot,
                DocumentationEvidence.json(DocumentationEvidence.snapshot(tempDir, List.of("src"))));
        Files.writeString(doc,
                "Producer forwards to consumer; payload archive is separate; logs contain only event IDs.\n");
        Files.writeString(evidence,
                "Executed event-42: archive received payload; event logger received only its ID.\n");
        DocumentationEvidence.record(tempDir, doc, snapshot, evidence, "complete");
        assertEquals("unchanged", DocumentationEvidence.check(tempDir, doc).status());
    }

    private static String consumer(boolean archive) {
        return """
                - from:
                    uri: direct:consumer
                    steps:
                """ + (archive ? "      - process:\n          ref: archive\n" : "") + """
                      - process:
                          ref: eventLog
                """;
    }

    private static void exercise(Path producer, Path consumer, List<String> logs, List<String> archive)
            throws Exception {
        Main main = new Main();
        main.bind("eventLog", (Processor) exchange -> logs.add(exchange.getIn().getHeader("eventId", String.class)));
        main.bind("archive", (Processor) exchange -> archive.add(exchange.getIn().getBody(String.class)));
        main.configure().withRoutesIncludePattern("file:" + producer + ",file:" + consumer);
        try {
            main.start();
            try (var template = main.getCamelContext().createProducerTemplate()) {
                template.sendBodyAndHeader("direct:incoming", "sensitive-payload", "eventId", "event-42");
            }
        } finally {
            main.stop();
        }
    }

    private static Path write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
