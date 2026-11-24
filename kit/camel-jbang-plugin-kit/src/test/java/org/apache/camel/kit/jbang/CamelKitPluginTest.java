package org.apache.camel.kit.jbang;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.CommandTree;
import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.CommandRunner;
import org.apache.camel.kit.command.InitCommand;
import org.apache.camel.kit.command.doc.DocCommand;
import org.apache.camel.kit.command.pipeline.NextIdCommand;
import org.apache.camel.kit.command.ship.ShipCommand;
import org.apache.camel.kit.output.SystemPrinter;
import org.apache.camel.kit.ship.context.ShipContext;

import org.aesh.command.impl.parser.ArgfileExpander;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("cli-compat")
class CamelKitPluginTest {

    @TempDir
    Path tempDir;

    @Test
    void kitInitDefaultsToBob2WhenAgentOptionIsOmitted() throws Exception {
        InitCommand command = new InitCommand(new CamelKitMain(new SystemPrinter()));

        new CommandRunner(command).parse("orders");

        assertEquals("bob2", command.ai);
    }

    @Test
    void kitInitAcceptsCodexAndShowsItInHelp() throws Exception {
        InitCommand command = new InitCommand(new CamelKitMain(new SystemPrinter()));
        CommandRunner commandLine = new CommandRunner(command);

        commandLine.parse("orders", "--ai", "codex");

        assertEquals("codex", command.ai);
        assertTrue(commandLine.parser().printHelp().contains("codex"));
    }

    @Test
    void registersDocAndNextIdUnderKitCommand() {
        CamelJBangMain main = new CamelJBangMain();
        CommandTree root = new CommandTree(main);

        new CamelKitPlugin().customize(root, main);

        CommandTree kit = root.getSubcommands().get("kit");
        assertNotNull(kit);
        assertInstanceOf(DocCommand.class, kit.getSubcommands().get("doc").getCommand());
        assertInstanceOf(NextIdCommand.class, kit.getSubcommands().get("nextId").getCommand());
    }

    @Test
    void hookIsDiscoverableThroughTheCamelHost() throws Exception {
        var main = new CamelJBangMain();
        var root = new CommandTree(main);
        new CamelKitPlugin().customize(root, main);
        var output = new StringWriter();
        root.setOutput(output::write);
        assertEquals(0, root.execute("kit", "hook", "--help").getResultValue());
        assertTrue(output.toString().contains("--result"), output::toString);
        assertTrue(output.toString().contains("camel kit hook"), output::toString);
    }

    @Test
    void acceptsHostEscapedLiteralShipContextAndCamelArgumentFiles() throws Exception {
        Path context = Files.writeString(tempDir.resolve("ship-context"), "EXPANDED VALUE");
        Path arguments = Files.writeString(
                tempDir.resolve("kit-arguments"), "kit init orders --ai codex");
        String literal = "@" + context.toAbsolutePath();
        CamelJBangMain main = new CamelJBangMain();
        CommandTree root = new CommandTree(main);
        new CamelKitPlugin().customize(root, main);
        CommandTree ship = root.getSubcommands().get("kit").getSubcommands().get("ship");
        StringWriter output = new StringWriter();
        root.setOutput(output::write);

        assertInstanceOf(ShipCommand.class, ship.getCommand());
        assertEquals("camel kit ship", ship.getQualifiedName());
        assertEquals(List.of(new ShipContext.TextInput(literal)),
                inputs(parseShip(main, "kit", "ship", "--text", "@" + literal)));
        assertEquals(List.of(new ShipContext.DocumentInput(Path.of("@notes.md"))),
                inputs(parseShip(main, "kit", "ship", "--document", "@@notes.md")));
        assertEquals(List.of(new ShipContext.TextInput(literal)),
                inputs(parseShip(main, "kit", "--version=true", "ship", "--text", "@" + literal)));
        assertEquals(List.of(new ShipContext.DocumentInput(context.toAbsolutePath())),
                inputs(parseShip(main, "kit", "ship", "--document", context.toAbsolutePath().toString())));
        root.parse(ArgfileExpander.expand(new String[]{"@" + arguments.toAbsolutePath()}));
        InitCommand init
                = (InitCommand) root.getSubcommands().get("kit").getSubcommands().get("init").getCommand();
        assertEquals("orders", init.projectName);
        assertEquals("codex", init.ai);

        assertEquals(0, root.execute("kit", "ship", "--help").getResultValue());
        assertTrue(output.toString().contains("Usage: camel kit ship"), output.toString());
    }

    private static CommandTree parseShip(CamelJBangMain main, String... args) throws Exception {
        var root = new CommandTree(main);
        new CamelKitPlugin().customize(root, main);
        root.parse(ArgfileExpander.expand(args));
        return root.getSubcommands().get("kit").getSubcommands().get("ship");
    }

    private static List<ShipContext.Input> inputs(CommandTree ship) {
        return ((ShipCommand) ship.getCommand()).inputs();
    }
}
