package org.apache.camel.kit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.kit.jbang.CamelKitPlugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real Camel startup, including plugin discovery, argument files and redirected process output. */
class HostedCliCompatibilityTest {
    @TempDir
    Path directory;

    @Test
    void discoversPluginAndKeepsMachineOutputClean() throws Exception {
        Result version = run("kit", "--version");
        assertEquals(0, version.exit, version.err);
        assertEquals(new org.apache.camel.kit.jbang.KitVersionProvider().getVersion()[0], version.out.strip());
        Result help = run("kit", "ship", "--help");
        assertEquals(0, help.exit, help.err);
        assertTrue(help.out.contains("Usage: camel kit ship"), help.out);
        Result doctor = run("kit", "doctor", "--json", "--project-dir", directory.toString());
        assertEquals(1, doctor.exit, doctor.err);
        assertEquals("FAIL", new ObjectMapper().readTree(doctor.out).path("status").asText());
        assertTrue(doctor.err.isBlank(), doctor.err);
        Result invalid = run("kit", "ship", "--status", "one", "--abort", "two",
                "--project-dir", directory.toString());
        assertEquals(2, invalid.exit, invalid.err);
        assertTrue(invalid.out.isBlank(), invalid.out);
        assertFalse(Files.exists(directory.resolve(".camel-kit")));
    }

    @Test
    void hostExpandsFilesAndKeepsEscapedAndAttachedContextLiteral() throws Exception {
        Path arguments = Files.writeString(directory.resolve("arguments"), "kit --version");
        Result fromFile = run("@" + arguments);
        assertEquals(0, fromFile.exit, fromFile.err);
        Path context = Files.writeString(directory.resolve("context"), "--status missing");
        // Expansion produces competing options, while escaped/attached tokens reach Kit as context.
        Result expanded = run("kit", "ship", "--text", "@" + context);
        assertEquals(2, expanded.exit, expanded.err);
        assertTrue(expanded.err.contains("requires a value"), expanded.err);
        for (String[] input : List.of(new String[]{"--text", "@@" + context},
                new String[]{"--text=@" + context}, new String[]{"--text", ""},
                new String[]{"--text", "C:\\work\\quoted \"value\", $(unused);|"})) {
            List<String> args = new ArrayList<>(List.of("kit", "ship", "--status", "missing"));
            args.addAll(List.of(input));
            Result literal = run(args.toArray(String[]::new));
            assertEquals(2, literal.exit, literal.err);
            assertTrue(literal.err.contains("only valid when starting or resuming"), literal.err);
        }
        Path camelArguments = Files.writeString(directory.resolve("camel-arguments"), "--version");
        Result camel = run("@" + camelArguments);
        assertEquals(0, camel.exit, camel.err);
        assertFalse(camel.out.isBlank());
        assertNotEquals(new org.apache.camel.kit.jbang.KitVersionProvider().getVersion()[0], camel.out.strip());
    }

    private Result run(String... arguments) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path classes = Path.of(CamelKitPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path jar = directory.resolve("camel-jbang-plugin-kit.jar");
        if (!Files.exists(jar)) {
            // Camel discovers plugins in JARs, not target/classes directories.
            try (var archive = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
                for (Path file : files.filter(path -> !path.equals(classes)).toList()) {
                    String name = classes.relativize(file).toString().replace('\\', '/');
                    archive.putNextEntry(new JarEntry(name + (Files.isDirectory(file) ? "/" : "")));
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, archive);
                    }
                    archive.closeEntry();
                }
            }
        }
        classpath = classpath.replace(classes.toString(), jar.toString());
        Path javaArguments = directory.resolve("java.args");
        Files.writeString(javaArguments, "--class-path\n" + quote(classpath) + "\n"
                                         + quote("-Duser.home=" + directory) + "\n" + HostProcess.class.getName()
                                         + "\n");
        List<String> command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "@" + javaArguments));
        command.addAll(List.of(arguments));
        Path out = directory.resolve("stdout"), err = directory.resolve("stderr");
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().put("CAMEL_CLI_PLUGIN_CONFIG", directory.resolve("plugins.json").toString());
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Camel host did not exit");
            return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record Result(int exit, String out, String err) {
    }

    public static class HostProcess {
        public static void main(String[] args) {
            CamelJBangMain.run(args);
        }
    }
}
