package org.apache.camel.kit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.camel.kit.command.ship.ShipCommand;
import org.apache.camel.kit.ship.context.ShipContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamelKitMainShipTest {

    @TempDir
    Path tempDir;

    @Test
    void registersShipWithQualifiedHelp() {
        CamelKitMain main = new CamelKitMain();
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
        CamelKitMain main = new CamelKitMain();
        try {
            CommandRunner root = CamelKitMain.commandLine(main, "ship", "--text", literal);

            root.parse("ship", "--text", literal);
            assertEquals(List.of(new ShipContext.TextInput(literal)), inputs(root));
            root.parse("ship", "--document", literal);
            assertEquals(List.of(new ShipContext.DocumentInput(Path.of(literal))), inputs(root));

        } finally {
            main.closeTerminal();
        }
    }

    @Test
    void retainsArgumentFileExpansionForOtherStandaloneInvocations() throws Exception {
        Path arguments = Files.writeString(tempDir.resolve("root-arguments"), "--version\n");
        CamelKitMain main = new CamelKitMain();
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
