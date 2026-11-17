package org.apache.camel.kit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.apache.camel.kit.command.InitCommand;
import org.apache.camel.kit.command.ship.ShipCommand;
import org.apache.camel.kit.output.SystemPrinter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Tag("cli-compat")
class AeshCliCompatibilityTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {
            "--text abc --text", "--text --json", "--text --document=foo", "--text --",
            "--status x --status", "--property a=b --property", "--json --json", "--json=true --json=false",
            "--json=0"})
    void rejectsMalformedShipOptionsBeforeExecution(String arguments) {
        var error = new StringWriter();
        assertEquals(2, new CommandRunner(new ShipCommand()).setErr(new PrintWriter(error))
                .execute(Stream
                        .concat(Stream.of("--project-dir", directory.toString()),
                                Arrays.stream(arguments.split(" ")))
                        .toArray(String[]::new)),
                error.toString());
        assertFalse(Files.exists(directory.resolve(".camel-kit")));
        assertFalse(error.toString().isBlank());
    }

    @Test
    void informationFlagsKeepMalformedOptionsStrictAcrossTheCommandTree() {
        var main = new CamelKitMain(new SystemPrinter());
        var output = new StringWriter();
        var error = new StringWriter();
        var runner = CamelKitMain.commandLine(main).setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
        for (String[] arguments : List.of(
                new String[]{"--help", "init", "--ai"},
                new String[]{"--version", "init", "--ai", "codex", "--ai", "claude"},
                new String[]{"--help", "ship", "--json=bad"},
                new String[]{"--help", "ship", "--text"},
                new String[]{"--help", "--help"},
                new String[]{"--help", "-hV"},
                new String[]{"--help", "init", "--silent", "--silent"},
                new String[]{"--help", "-hX"},
                new String[]{"--version", "graph", "migration-context", "route", "--depth", "invalid"},
                new String[]{"--help", "init", "--config", "bad\u0000path"})) {
            assertEquals(2, runner.execute(arguments), List.of(arguments).toString());
        }
        assertEquals(0, runner.execute("--help", "graph", "neighbors"));
        assertEquals(0, runner.execute("--version", "init", "--unknown"));
        assertEquals(0, runner.execute("--help", "init", "one", "two"));
    }

    @Test
    void sharesHelpOnlyAcrossCommandsThatAlreadyExposeIt() {
        var parser = CamelKitMain.commandLine(new CamelKitMain(new SystemPrinter())).parser();
        assertNotNull(parser.getProcessedCommand().findLongOptionNoActivatorCheck("help"));
        for (String name : List.of("init", "doctor", "ship")) {
            assertNotNull(parser.getChildParser(name).getProcessedCommand().findLongOptionNoActivatorCheck("help"),
                    name);
        }
        for (String name : List.of("graph", "doc", "plan", "nextId")) {
            assertNull(parser.getChildParser(name).getProcessedCommand().findLongOptionNoActivatorCheck("help"), name);
        }
    }

    @Test
    void parentHelpAndVersionNeverExecuteChildren() {
        CamelKitMain main = new CamelKitMain(new SystemPrinter());
        try {
            var output = new StringWriter();
            var error = new StringWriter();
            var runner = CamelKitMain.commandLine(main).setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
            Path project = directory.resolve("untouched");
            for (String flag : List.of("--help", "--help=false", "-V", "--version=false", "-hV", "-Vh")) {
                output.getBuffer().setLength(0);
                assertEquals(0, runner.execute(flag, "init", project.toString(), "--ai", "codex"));
                assertFalse(Files.exists(project));
                assertFalse(output.toString().contains("Usage: camel-kit init"), output.toString());
                assertFalse(output.toString().isBlank());
            }
            assertEquals(0, runner.execute("--version", "--invalid"));
            assertEquals(0, runner.execute("--invalid", "--version"));
            assertEquals(2, runner.execute("--help", "--version=bad"));
            assertEquals(2, runner.execute("--version", "--version"));
            assertEquals(2, runner.execute("ship", "--help", "--text"));
            assertEquals(2, runner.execute("ship", "-V", "--text"));
            assertEquals(0, runner.execute("ship", "--version=false", "--status", "missing"));
            assertEquals(2, runner.execute("ship", "--text", "-V"));
            assertEquals(2, runner.execute("doc", "help", "unknown-command"));
            assertEquals(2, runner.execute("doc", "help", "check bogus"));
            assertEquals(0, runner.execute("--help", "init", "one", "two"));
        } finally {
            main.closeTerminal();
        }
    }

    @Test
    void preservesTokenizedValuesCollectionArityAndResetsDefaults() throws Exception {
        CamelKitMain main = new CamelKitMain(new SystemPrinter());
        try {
            InitCommand init = new InitCommand(main);
            CommandRunner runner = new CommandRunner(init);
            List<String> values
                    = List.of("empty=", "comma=a,b", "quoted=\"x y\"", "shell=$(touch never);|", "path=C:\\work\\a b");
            runner.parse("orders", "-p", values.get(0), values.get(1), values.get(2), values.get(3),
                    "-p", values.get(4), "--config", "relative config.properties", "--silent=false", "--ai", "codex");
            assertEquals(values, init.properties);
            assertEquals(Path.of("relative config.properties"), init.configFile);
            assertFalse(init.silent);
            assertEquals("codex", init.ai);
            runner.parse("second");
            assertEquals("second", init.projectName);
            assertEquals("bob2", init.ai);
            assertNull(init.configFile);
            assertNull(init.properties);
            runner.parse("third", "-cfoo=bar");
            assertEquals(Path.of("foo=bar"), init.configFile);
        } finally {
            main.closeTerminal();
        }
    }

    @Test
    void rejectsDuplicateOptionsAndInvalidPathsBeforeWriting() {
        CamelKitMain main = new CamelKitMain(new SystemPrinter());
        try {
            var error = new StringWriter();
            var runner = CamelKitMain.commandLine(main).setErr(new PrintWriter(error));
            Path project = directory.resolve("untouched");
            assertEquals(2, runner.execute("init", project.toString(), "--ai", "codex", "--ai", "claude"));
            assertFalse(Files.exists(project));
            assertEquals(2, runner.execute("init", project.toString(), "--config", "bad\u0000path"));
            assertFalse(Files.exists(project));
            assertFalse(error.toString().isBlank());
        } finally {
            main.closeTerminal();
        }
    }

    @Test
    void nestedHelpAndBothVersionSpellingsStayOnStdout() {
        CamelKitMain main = new CamelKitMain(new SystemPrinter());
        try {
            var output = new StringWriter();
            var error = new StringWriter();
            var runner = CamelKitMain.commandLine(main).setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
            for (String[] args : List.of(new String[]{"init", "--help"},
                    new String[]{"graph", "help", "find"}, new String[]{"doc", "help", "record"},
                    new String[]{"-V"}, new String[]{"--version"})) {
                assertEquals(0, runner.execute(args), List.of(args).toString());
            }
            assertTrue(output.toString().contains("Usage: camel-kit graph find"), output.toString());
            assertTrue(output.toString().contains("Usage: camel-kit doc record"), output.toString());
            assertTrue(error.toString().isEmpty(), error.toString());
        } finally {
            main.closeTerminal();
        }
    }
}
