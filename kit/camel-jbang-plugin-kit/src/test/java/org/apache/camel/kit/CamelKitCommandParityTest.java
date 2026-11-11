package org.apache.camel.kit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.kit.jbang.CamelKitPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamelKitCommandParityTest {

    private static final Set<String> PUBLIC_SKILLS
            = Set.of("camel-start", "camel-brainstorm", "camel-migrate", "camel-plan",
                    "camel-execute", "camel-validate", "camel-ship", "camel-knowledge", "camel-debug");

    @TempDir
    Path tempDir;

    @Test
    void bob2InitAndRegenerationExposeTheSamePublicSkills() throws Exception {
        CamelKitMain standaloneMain = new CamelKitMain();
        standaloneMain.disableTui();
        CommandLine standalone = CamelKitMain.commandLine(standaloneMain);
        CamelJBangMain main = new CamelJBangMain();
        CommandLine plugin = new CommandLine(main);
        new CamelKitPlugin().customize(plugin, main);
        Path standaloneDir = tempDir.resolve("standalone");
        Path pluginDir = tempDir.resolve("plugin");

        for (int initialization = 0; initialization < 2; initialization++) {
            assertEquals(0, standalone.execute("init", standaloneDir.toString(), "--ai", "bob2",
                    "--silent", "--no-fetch", "--force"));
            assertEquals(0, plugin.execute("kit", "init", pluginDir.toString(), "--ai", "bob2",
                    "--silent", "--no-fetch", "--force"));
            for (String name : PUBLIC_SKILLS) {
                String relative = ".bob/skills/" + name + "/SKILL.md";
                String skill = Files.readString(standaloneDir.resolve(relative));
                String pluginSkill = Files.readString(pluginDir.resolve(relative));
                assertTrue(skill.contains("\nuser-invocable: true\n"), name);
                // Both entry points share this JVM's process-based CLI prefix detection.
                assertEquals(skill, pluginSkill, name);
            }
        }
    }

    @Test
    void pluginForwardsWithCitrusMcpOption() throws Exception {
        CamelKitMain standaloneMain = new CamelKitMain();
        standaloneMain.disableTui();
        CommandLine standalone = CamelKitMain.commandLine(standaloneMain);
        CamelJBangMain main = new CamelJBangMain();
        CommandLine plugin = new CommandLine(main);
        new CamelKitPlugin().customize(plugin, main);

        Path standaloneDefault = tempDir.resolve("standalone-default");
        Path pluginDefault = tempDir.resolve("plugin-default");
        Path standaloneCitrus = tempDir.resolve("standalone-citrus");
        Path pluginCitrus = tempDir.resolve("plugin-citrus");

        assertEquals(0, standalone.execute("init", standaloneDefault.toString(), "--ai", "bob2",
                "--silent", "--no-fetch"));
        assertEquals(0, plugin.execute("kit", "init", pluginDefault.toString(), "--ai", "bob2",
                "--silent", "--no-fetch"));
        assertEquals(0, standalone.execute("init", standaloneCitrus.toString(), "--ai", "bob2",
                "--silent", "--no-fetch", "--with-citrus-mcp"));
        assertEquals(0, plugin.execute("kit", "init", pluginCitrus.toString(), "--ai", "bob2",
                "--silent", "--no-fetch", "--with-citrus-mcp"));

        Properties defaultProps = loadConfig(standaloneDefault);
        Properties pluginDefaultProps = loadConfig(pluginDefault);
        Properties citrusProps = loadConfig(standaloneCitrus);
        Properties pluginCitrusProps = loadConfig(pluginCitrus);

        assertEquals("false", defaultProps.getProperty("citrus.mcp.enabled"));
        assertEquals("false", pluginDefaultProps.getProperty("citrus.mcp.enabled"));
        assertEquals("true", citrusProps.getProperty("citrus.mcp.enabled"));
        assertEquals("true", pluginCitrusProps.getProperty("citrus.mcp.enabled"));

        String defaultMcp = Files.readString(standaloneDefault.resolve(".bob/mcp.json"));
        String citrusMcp = Files.readString(standaloneCitrus.resolve(".bob/mcp.json"));
        String pluginCitrusMcp = Files.readString(pluginCitrus.resolve(".bob/mcp.json"));
        assertFalse(defaultMcp.contains("\"citrus\""), "Default init must not include citrus MCP server");
        assertTrue(citrusMcp.contains("\"citrus\""), "Standalone --with-citrus-mcp must include citrus");
        assertTrue(pluginCitrusMcp.contains("\"citrus\""), "Plugin --with-citrus-mcp must include citrus");
    }

    @Test
    void pluginForwardsTheExistingProjectOptions() throws Exception {
        CamelKitMain standaloneMain = new CamelKitMain();
        standaloneMain.disableTui();
        CommandLine standalone = CamelKitMain.commandLine(standaloneMain);
        CamelJBangMain main = new CamelJBangMain();
        CommandLine plugin = new CommandLine(main);
        new CamelKitPlugin().customize(plugin, main);

        // --dry-run: nothing is written
        for (String[] entry : new String[][]{{"standalone-dry"}, {"plugin-dry"}}) {
            Path dir = existingProject(entry[0], "#!/bin/sh\nexit 0\n");
            int exit = entry[0].startsWith("plugin")
                    ? plugin.execute("kit", "init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force",
                            "--dry-run")
                    : standalone.execute("init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force",
                            "--dry-run");
            assertEquals(0, exit, entry[0]);
            assertFalse(Files.exists(dir.resolve(".camel-kit")), entry[0] + ": --dry-run must not write anything");
        }

        // --record-baseline: the project's own wrapper is run and the result is recorded
        for (String name : new String[]{"standalone-baseline", "plugin-baseline"}) {
            Path dir = existingProject(name, "#!/bin/sh\ntouch mvnw-was-run\nexit 0\n");
            int exit = name.startsWith("plugin")
                    ? plugin.execute("kit", "init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force",
                            "--record-baseline")
                    : standalone.execute("init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force",
                            "--record-baseline");
            assertEquals(0, exit, name);
            assertTrue(Files.exists(dir.resolve("mvnw-was-run")), name + ": --record-baseline must run the wrapper");
            assertEquals("passed", loadConfig(dir).getProperty("project.baselineTestStatus"), name);
        }

        // without --replace-wrapper the project's wrapper is kept; with it, it is replaced
        for (String name : new String[]{"standalone-keep", "plugin-keep"}) {
            Path dir = existingProject(name, "#!/bin/sh\n# ORIGINAL\n");
            int exit = name.startsWith("plugin")
                    ? plugin.execute("kit", "init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force")
                    : standalone.execute("init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force");
            assertEquals(0, exit, name);
            assertTrue(Files.readString(dir.resolve("mvnw")).contains("ORIGINAL"), name);
        }
        for (String name : new String[]{"standalone-replace", "plugin-replace"}) {
            Path dir = existingProject(name, "#!/bin/sh\n# ORIGINAL\n");
            int exit = name.startsWith("plugin")
                    ? plugin.execute("kit", "init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force",
                            "--replace-wrapper")
                    : standalone.execute("init", dir.toString(), "--ai", "bob2", "--silent", "--no-fetch", "--force",
                            "--replace-wrapper");
            assertEquals(0, exit, name);
            assertFalse(Files.readString(dir.resolve("mvnw")).contains("ORIGINAL"),
                    name + ": --replace-wrapper must replace the project's wrapper");
        }
    }

    private Path existingProject(String name, String wrapperScript) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>existing</artifactId>
                  <version>1.0.0</version>
                  <properties><camel.version>4.8.0</camel.version></properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.camel</groupId>
                      <artifactId>camel-main</artifactId>
                      <version>${camel.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.createDirectories(dir.resolve("src/main/resources/camel"));
        Files.writeString(dir.resolve("src/main/resources/camel/r.yaml"),
                "- route:\n    id: r\n    from:\n      uri: timer:t\n      steps:\n        - log: hi\n");
        Files.createDirectories(dir.resolve("src/test/java/com/example"));
        Files.writeString(dir.resolve("src/test/java/com/example/RouteTest.java"),
                "package com.example;\nclass RouteTest {\n}\n");
        Path wrapper = dir.resolve("mvnw");
        Files.writeString(wrapper, wrapperScript);
        wrapper.toFile().setExecutable(true);
        return dir;
    }

    private Properties loadConfig(Path workspace) throws Exception {
        Properties config = new Properties();
        try (var input = Files.newInputStream(workspace.resolve(".camel-kit/config.properties"))) {
            config.load(input);
        }
        return config;
    }

    @ParameterizedTest
    @CsvSource({"false,bob2", "true,bob2", "false,copilot", "true,copilot", "false,claude", "true,claude"})
    void nativeInitAndRegenerationDetectThePrefixInSeparateProcesses(boolean plugin, String agent) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        String expectedPrefix = plugin ? "camel kit" : "camel-kit";
        // The argument file keeps dependency paths out of process-based prefix detection.
        Path arguments = tempDir.resolve(plugin ? "camel-jbang.args" : "camel-kit.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "--class-path\n" + quoteJavaArgument(classpath) + "\n"
                                     + InitProcess.class.getName() + "\n" + plugin + "\n"
                                     + quoteJavaArgument(workspace.toString()) + "\n" + agent + "\n");

        for (int initialization = 0; initialization < 2; initialization++) {
            Path log = tempDir.resolve("init-" + initialization + ".log");
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + arguments)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Initialization process timed out: " + log);
                assertEquals(0, process.exitValue(), Files.readString(log));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            Properties config = new Properties();
            try (var input = Files.newInputStream(workspace.resolve(".camel-kit/config.properties"))) {
                config.load(input);
            }
            assertEquals(expectedPrefix, config.getProperty("project.command-prefix"));
            for (String name : PUBLIC_SKILLS) {
                String skillsRoot = switch (agent) {
                    case "bob2" -> ".bob/skills/";
                    case "copilot" -> ".github/skills/";
                    default -> ".claude/skills/";
                };
                String skill = Files.readString(workspace.resolve(skillsRoot + name + "/SKILL.md"));
                assertFalse(skill.contains("\nuser-invocable: false\n"), name);
                if (agent.equals("bob2")) {
                    assertTrue(skill.contains("\nuser-invocable: true\n"), name);
                }
                if (name.equals("camel-ship")) {
                    assertTrue(skill.contains(
                            expectedPrefix + " ship --backend " + agent + "-native --json"));
                }
            }
        }
    }

    private static String quoteJavaArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static class InitProcess {
        public static void main(String[] args) {
            CommandLine command;
            boolean plugin = Boolean.parseBoolean(args[0]);
            var invocation
                    = new ArrayList<>(List.of("init", args[1], "--ai", args[2], "--silent", "--no-fetch", "--force"));
            if (plugin) {
                CamelJBangMain main = new CamelJBangMain();
                command = new CommandLine(main);
                new CamelKitPlugin().customize(command, main);
                invocation.add(0, "kit");
            } else {
                CamelKitMain main = new CamelKitMain();
                main.disableTui();
                command = CamelKitMain.commandLine(main);
            }
            System.exit(command.execute(invocation.toArray(String[]::new)));
        }
    }

    @Test
    void pluginRegistersStandaloneCommandSurface() {
        CamelJBangMain main = new CamelJBangMain();
        CommandLine pluginRoot = new CommandLine(main);
        new CamelKitPlugin().customize(pluginRoot, main);

        assertEquals(CamelKitMain.commandLine(new CamelKitMain()).getSubcommands().keySet(),
                pluginRoot.getSubcommands().get("kit").getSubcommands().keySet());
    }
}
