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
import org.apache.camel.kit.jbang.KitVersionProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real Camel startup, including plugin discovery, argument files and redirected process output. */
@Tag("cli-compat")
class HostedCliCompatibilityTest {
    @TempDir
    Path directory;

    @Test
    void discoversPluginAndKeepsMachineOutputClean() throws Exception {
        Result version = run("kit", "--version");
        assertEquals(0, version.exit, version.err);
        assertEquals(new KitVersionProvider().getVersion()[0], version.out.strip());
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
    void initWritesPropertyOverridesToTheLauncherAndGuides() throws Exception {
        Result init = run("kit", "init", "orders", "--ai", "claude", "--no-fetch",
                "-p", "camel.main.version=4.99.1", "-p", "camel.mcp.version=4.99.2",
                "-p", "maven.dependency.plugin.version=3.99.1");
        assertEquals(0, init.exit, init.err);
        Path targetDir = directory.resolve("orders");
        assertTrue(Files.readString(targetDir.resolve(".mcp.json")).contains("camel-jbang-mcp:4.99.2"));
        Path skills = targetDir.resolve(".claude/skills");
        assertTrue(Files.readString(skills.resolve("shared/mcp-setup.md"))
                .contains("org.apache.camel:camel-catalog:4.99.1"));
        assertTrue(Files.readString(skills.resolve("camel-brainstorm/guides/version-selection.md"))
                .contains("maven-dependency-plugin:3.99.1:tree"));
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
        assertNotEquals(new KitVersionProvider().getVersion()[0], camel.out.strip());
    }

    @Test
    void redirectedStandaloneBannerIsPlainEvenWithAnImageCapableEnvironment() throws Exception {
        Result banner = run(CamelKitMain.class);
        assertEquals(0, banner.exit, banner.err);
        assertTrue(banner.err.isBlank(), banner.err);
        assertTrue(banner.out.contains("Camel-Kit \u2014 Design Apache Camel Integrations with AI"), banner.out);
        assertTrue(banner.out.contains("Usage: camel-kit"), banner.out);
        assertFalse(banner.out.contains("\u001b"), "Redirected output contains terminal control sequences");
    }

    @Test
    void missingUnescapedContextFileFailsBeforeKitExecutes() throws Exception {
        Result missing = run("kit", "ship", "--text", "@missing-context", "--project-dir", directory.toString());
        assertEquals(2, missing.exit, missing.err);
        assertTrue(missing.out.isBlank(), missing.out);
        assertTrue(missing.err.contains("Argument file not found"), missing.err);
        assertFalse(Files.exists(directory.resolve(".camel-kit")));
    }

    private Result run(String... arguments) throws Exception {
        return run(HostProcess.class, arguments);
    }

    private Result run(Class<?> entryPoint, String... arguments) throws Exception {
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
        Files.writeString(javaArguments,
                "-Dfile.encoding=UTF-8\n-Dstdout.encoding=UTF-8\n-Dstderr.encoding=UTF-8\n--class-path\n"
                                         + quote(classpath) + "\n"
                                         + quote("-Duser.home=" + directory) + "\n" + entryPoint.getName()
                                         + "\n");
        List<String> command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        if (entryPoint == CamelKitMain.class) {
            Path root = Path
                    .of(HostedCliCompatibilityTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path launcher = Path.of("kit/camel-kit-main/src/main/jbang/main/CamelKit.java");
            while (root != null && !Files.isRegularFile(root.resolve(launcher))) {
                root = root.getParent();
            }
            assertNotNull(root, "Could not locate the shipped JBang launcher");
            List<String> source = Files.readAllLines(root.resolve(launcher));
            assertEquals(source, Files.readAllLines(root.resolve("kit/camel-kit-main/dist/CamelKit.java")),
                    "Source and distribution launchers must have the same JVM options and dependencies");
            for (String line : source) {
                if (line.startsWith("//JAVA_OPTIONS ")) {
                    command.addAll(List.of(line.substring("//JAVA_OPTIONS ".length()).trim().split("\\s+")));
                }
            }
        }
        command.add("@" + javaArguments);
        command.addAll(List.of(arguments));
        Path out = directory.resolve("stdout"), err = directory.resolve("stderr");
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        if (entryPoint == CamelKitMain.class) {
            builder.environment().put("TERM", "xterm-kitty");
            builder.environment().put("KITTY_WINDOW_ID", "185");
        }
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
