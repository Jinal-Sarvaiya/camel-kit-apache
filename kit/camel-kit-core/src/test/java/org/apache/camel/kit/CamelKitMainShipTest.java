package org.apache.camel.kit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.camel.kit.command.ship.ShipCommand;
import org.apache.camel.kit.output.SystemPrinter;
import org.apache.camel.kit.ship.context.ShipContext;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("cli-compat")
class CamelKitMainShipTest {

    @TempDir
    Path tempDir;

    @Test
    void registersShipWithQualifiedHelp() {
        CamelKitMain main = new CamelKitMain(new SystemPrinter());
        try {
            CommandRunner root = CamelKitMain.commandLine(main, "ship", "--help");
            var ship = root.parser().getChildParser("ship");
            StringWriter output = new StringWriter();
            root.setOut(new PrintWriter(output, true));

            assertNotNull(ship);
            assertEquals(0, root.execute("ship", "--help"));
            assertTrue(output.toString().contains("Usage: camel-kit ship"), output.toString());
            assertTrue(output.toString().contains("-c, --config"), output.toString());
            assertTrue(output.toString().contains("-p, --property"), output.toString());
        } finally {
            main.closeTerminal();
        }
    }

    @Test
    void preservesLiteralAtContextForShip() throws Exception {
        Path arguments = Files.writeString(tempDir.resolve("ship-arguments"), "EXPANDED");
        String literal = "@" + arguments.toAbsolutePath();
        CamelKitMain main = new CamelKitMain(new SystemPrinter());
        try {
            CommandRunner root = CamelKitMain.commandLine(main, "ship", "--text", literal);

            root.parse("ship", "--text", literal);
            assertEquals(List.of(new ShipContext.TextInput(literal)), inputs(root));
            root.parse("ship", "--document", "@notes.md");
            assertEquals(List.of(new ShipContext.DocumentInput(Path.of("@notes.md"))), inputs(root));
            root.parse("ship", "--document", arguments.toAbsolutePath().toString());
            assertEquals(List.of(new ShipContext.DocumentInput(arguments.toAbsolutePath())), inputs(root));

        } finally {
            main.closeTerminal();
        }
    }

    @Test
    void retainsArgumentFileExpansionForOtherStandaloneInvocations() throws Exception {
        Path arguments = Files.writeString(tempDir.resolve("root-arguments"), "--version\n");
        CamelKitMain main = new CamelKitMain(new SystemPrinter());
        try {
            CommandRunner root = CamelKitMain.commandLine(main, "@" + arguments.toAbsolutePath());

            root.parse("@" + arguments.toAbsolutePath());
            assertTrue(main.versionRequested);
        } finally {
            main.closeTerminal();
        }
    }

    private static List<ShipContext.Input> inputs(CommandRunner root) {
        return ((ShipCommand) root.parser().getChildParser("ship").getCommand()).inputs();
    }
}
