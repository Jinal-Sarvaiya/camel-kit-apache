package org.apache.camel.kit.generator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.camel.dsl.yaml.validator.CamelYamlParser;
import org.apache.camel.kit.config.AgentConfig;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.output.Printer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class MainRunGuidanceTest {

    private static final Pattern RUN_COMMAND = Pattern.compile(
            "(?m)(?:`|^# Run with: )((?:jbang camel@apache/camel|camel) run [^`\\n]+)");

    @TempDir
    Path tempDir;

    static Stream<String> agents() {
        return AgentRegistry.names().stream().sorted();
    }

    @Test
    void environmentProbeRouteParsesWithCamel() throws Exception {
        String guide;
        try (var resource = getClass().getResourceAsStream("/skills/camel-execute/guides/environment-probe.md")) {
            assertNotNull(resource);
            guide = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        var yaml = Pattern.compile("#### Route YAML.*?```yaml\\n(.*?)```", Pattern.DOTALL).matcher(guide);
        assertTrue(yaml.find(), "Missing probe route example");
        Path route = tempDir.resolve("probe-route.camel.yaml");
        Files.writeString(route, yaml.group(1));
        assertEquals(List.of(), new CamelYamlParser().parse(route.toFile()));
    }

    @ParameterizedTest
    @MethodSource("agents")
    void installedMainCommandsUseJBangAndKeepTheProjectVersion(String agentName) throws Exception {
        AgentConfig agent = AgentRegistry.get(agentName);
        Path project = tempDir.resolve(agentName);
        Files.createDirectories(project.resolve(".camel-kit"));
        Files.writeString(project.resolve(".camel-kit/config.properties"),
                "project.runtime=main\nproject.camelVersion=4.18.4\n");
        Path skills = project.resolve(agent.skillsDirectory());
        Path commands = agent.generatesCommandStubs() ? project.resolve(agent.commandDirectory()) : skills;
        InitContext context = new InitContext(
                agent, agentName, commands, skills, project, "camel-kit", Printer.noop());
        assertNotEquals("4.18.4", context.distribution().camelMainVersion());
        AgentGeneratorFactory.create(agentName).generate(context);

        for (String guide : List.of(
                "camel-execute/guides/environment-probe.md",
                "camel-verify/guides/error-taxonomy.md",
                "camel-migrate/SKILL.md",
                "shared/yaml-structure.md")) {
            String content = Files.readString(skills.resolve(guide));
            var matches = RUN_COMMAND.matcher(content).results().map(match -> match.group(1)).toList();
            assertEquals(1, matches.size(), guide + " must contain one executable Main command");
            String command = matches.getFirst();
            assertTrue(command.startsWith("jbang camel@apache/camel run "), guide);
            assertTrue(List.of(command.split("\\s+")).contains("--camel-version={project.camelVersion}"),
                    guide + " must preserve the project version in the command, not just the prose");
            assertTrue(command.contains("--properties=application.properties"), guide);
        }
        assertTrue(Files.readString(skills.resolve("shared/yaml-structure.md"))
                .contains("# Camel Version: {project.camelVersion}"));
    }
}
