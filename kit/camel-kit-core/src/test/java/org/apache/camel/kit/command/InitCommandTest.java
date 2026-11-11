package org.apache.camel.kit.command;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.generator.InvalidAgentConfigurationException;
import org.apache.camel.kit.output.Printer;
import org.apache.camel.kit.service.InitRequest;
import org.apache.camel.kit.service.InitResult;
import org.apache.camel.kit.service.InitService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class InitCommandTest {

    @Test
    void defaultsToBob2WhenAgentOptionIsOmitted() {
        InitCommand command = new InitCommand(new CamelKitMain());

        new CommandLine(command).parseArgs("orders");

        assertEquals("bob2", command.ai);
    }

    @Test
    void nextStepsUseCurrentSkillRouterCommands() {
        CapturingPrinter printer = new CapturingPrinter();
        CamelKitMain main = new CamelKitMain();
        main.setOut(printer);

        InitCommand command = new InitCommand(main);
        command.printNextSteps("orders", AgentRegistry.get("claude").name(), "claude");

        String output = printer.output();
        assertTrue(output.contains("/camel-start"));
        assertTrue(output.contains("/camel-migrate"));
        assertTrue(output.contains("/camel-debug"));
        assertTrue(output.contains("/camel-knowledge"));
        assertFalse(output.contains("/camel-design"));
        assertFalse(output.contains("/camel-verify"));
    }

    @Test
    void bob2NextStepsDistinguishShellSkillsAndIdeCommands() {
        CapturingPrinter printer = new CapturingPrinter();
        CamelKitMain main = new CamelKitMain();
        main.setOut(printer);

        new InitCommand(main).printNextSteps("orders", "Renamed Bob Display", "bob2");

        String output = printer.output();
        assertTrue(output.contains("Bob Shell"));
        assertTrue(output.contains("/skills"));
        assertTrue(output.contains("$camel-start"));
        assertTrue(output.contains("$camel-migrate"));
        assertTrue(output.contains("Bob IDE"));
        assertTrue(output.contains("/camel-start"));
    }

    @Test
    void copilotNextStepsUseSkillsInsteadOfCamelSlashCommands() {
        CapturingPrinter printer = new CapturingPrinter();
        CamelKitMain main = new CamelKitMain();
        main.setOut(printer);

        InitCommand command = new InitCommand(main);
        command.printNextSteps("orders", "Renamed Copilot Display", "copilot");

        String output = printer.output();
        assertTrue(output.contains("Use the /camel-start skill"));
        assertTrue(output.contains("/skills list"));
        assertTrue(output.contains("/mcp show"));
        assertFalse(output.contains("select camel-start"));
    }

    @Test
    void piNextStepsUseTrustAndSkillInvocation() {
        CapturingPrinter printer = new CapturingPrinter();
        CamelKitMain main = new CamelKitMain();
        main.setOut(printer);

        InitCommand command = new InitCommand(main);
        command.printNextSteps("orders", "Pi", "pi");

        String output = printer.output();
        assertTrue(output.contains("pi install npm:pi-mcp-adapter@2.11.0"));
        assertTrue(output.contains("/trust"));
        assertTrue(output.contains("/skill:camel-start"));
        assertTrue(output.contains("/mcp status"));
        assertFalse(output.contains("/camel-start  "));
    }

    @Test
    void codexOptionIsAcceptedAndShownInHelp() {
        InitCommand command = new InitCommand(new CamelKitMain());
        CommandLine commandLine = new CommandLine(command);

        commandLine.parseArgs("orders", "--ai", "codex");

        assertEquals("codex", command.ai);
        assertTrue(commandLine.getUsageMessage().contains("codex"));
    }

    @Test
    void codexNextStepsUseTrustSkillsAndMcpDiscovery() {
        CapturingPrinter printer = new CapturingPrinter();
        CamelKitMain main = new CamelKitMain();
        main.setOut(printer);

        InitCommand command = new InitCommand(main);
        command.printNextSteps("orders", "OpenAI Codex CLI", "codex");

        String output = printer.output();
        assertTrue(output.contains("trust"));
        assertTrue(output.contains("/skills"));
        assertTrue(output.contains("$camel-start"));
        assertTrue(output.contains("/mcp"));
        assertFalse(output.contains("/camel-start"));
    }

    @Test
    void invalidAgentConfigurationPrintsOneErrorLine(@TempDir Path tempDir) throws Exception {
        CapturingPrinter printer = new CapturingPrinter();
        CamelKitMain main = new CamelKitMain() {
            @Override
            public void printBanner() {
                // Keep this command-level error-path test independent of terminal image support.
            }
        };
        main.disableTui();
        main.setOut(printer);
        InitService failingService = new InitService() {
            @Override
            public InitResult initialize(InitRequest request) throws Exception {
                throw new InvalidAgentConfigurationException(
                        "existing opencode.json is not valid JSON or JSONC");
            }
        };
        InitCommand command = new InitCommand(main, failingService);
        command.projectName = tempDir.resolve("orders").toString();
        command.ai = "opencode";
        command.citrusVersion = "default";
        command.noFetch = true;

        int exitCode = command.doCall();

        assertEquals(1, exitCode);
        assertEquals(1, printer.output().lines()
                .filter(line -> line.contains("existing opencode.json"))
                .count());
        assertFalse(printer.output().contains("InvalidAgentConfigurationException"));
        assertFalse(printer.output().contains("\tat "));
    }

    @Test
    void existingAgentsMdDoesNotBlockAdoptionAndIsPreserved(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("orders");
        Files.createDirectories(project);
        Files.writeString(project.resolve("AGENTS.md"), "# Team rules\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n# project wrapper\n");
        CapturingPrinter printer = new CapturingPrinter();

        int exitCode = initCommand(project, printer).doCall();

        assertEquals(0, exitCode, printer.output());
        String agentsMd = Files.readString(project.resolve("AGENTS.md"));
        assertTrue(agentsMd.startsWith("# Team rules\n"), agentsMd);
        assertTrue(agentsMd.contains("camel-start"), agentsMd);
        assertEquals("#!/bin/sh\n# project wrapper\n", Files.readString(project.resolve("mvnw")));
        assertTrue(Files.isDirectory(project.resolve(".camel-kit")));
        assertTrue(printer.output().contains("Existing files preserved"), printer.output());
        assertTrue(printer.output().contains("mvnw"), printer.output());
    }

    @Test
    void dryRunListsPlannedChangesAndWritesNothing(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("orders");
        Files.createDirectories(project);
        Files.writeString(project.resolve("AGENTS.md"), "# Team rules\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n# project wrapper\n");
        CapturingPrinter printer = new CapturingPrinter();
        InitCommand command = initCommand(project, printer);
        command.dryRun = true;

        assertEquals(0, command.doCall(), printer.output());

        assertFalse(Files.exists(project.resolve(".camel-kit")));
        assertEquals("# Team rules\n", Files.readString(project.resolve("AGENTS.md")));
        try (var files = Files.list(project)) {
            assertEquals(2, files.count());
        }
        assertTrue(printer.output().lines().anyMatch(line -> line.contains("merge") && line.contains("AGENTS.md")),
                printer.output());
        assertTrue(printer.output().lines().anyMatch(line -> line.contains("preserve") && line.endsWith("mvnw")),
                printer.output());
        assertTrue(printer.output().lines().anyMatch(line -> line.contains("create") && line.contains("CLAUDE.md")),
                printer.output());
    }

    @Test
    void initializedProjectStillRequiresForceAndRejectsUnknownReplacement(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("orders");
        assertEquals(0, initCommand(project, new CapturingPrinter()).doCall());

        CapturingPrinter refused = new CapturingPrinter();
        assertEquals(1, initCommand(project, refused).doCall());
        assertTrue(refused.output().contains("Project already initialized"), refused.output());

        CapturingPrinter rejected = new CapturingPrinter();
        InitCommand command = initCommand(project, rejected);
        command.force = true;
        command.replace = java.util.List.of("pom.xml");
        assertEquals(1, command.doCall());
        assertTrue(rejected.output().contains("Cannot replace 'pom.xml'"), rejected.output());
    }

    private static InitCommand initCommand(Path project, CapturingPrinter printer) {
        CamelKitMain main = new CamelKitMain() {
            @Override
            public void printBanner() {
                // Keep command-level tests independent of terminal image support.
            }
        };
        main.disableTui();
        main.setOut(printer);
        InitCommand command = new InitCommand(main);
        command.projectName = project.toString();
        command.ai = "claude";
        command.citrusVersion = "default";
        command.noFetch = true;
        return command;
    }

    private static final class CapturingPrinter implements Printer {
        private final StringBuilder output = new StringBuilder();

        @Override
        public void println() {
            output.append(System.lineSeparator());
        }

        @Override
        public void println(String line) {
            output.append(line).append(System.lineSeparator());
        }

        @Override
        public void print(String value) {
            output.append(value);
        }

        String output() {
            return output.toString();
        }
    }
}
