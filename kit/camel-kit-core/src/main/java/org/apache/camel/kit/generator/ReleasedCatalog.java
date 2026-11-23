package org.apache.camel.kit.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.util.ConfinedPaths;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Known pre-ledger files for the selected assistant in an existing Camel Kit workspace. */
final class ReleasedCatalog {
    private ReleasedCatalog() {
    }

    static boolean hasWorkspaceConfiguration(Path root) throws IOException {
        Path config = root.resolve(".camel-kit/config.properties");
        ConfinedPaths.requirePath(root, config);
        String configuredAgent;
        if (Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            var settings = new Properties();
            try (var in = Files.newBufferedReader(config)) {
                settings.load(in);
            } catch (IllegalArgumentException malformed) {
                return false;
            }
            configuredAgent = settings.getProperty("agent.name");
        } else {
            Path yaml = root.resolve(".camel-kit/config.yaml");
            if (!Files.isRegularFile(yaml, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            ConfinedPaths.requirePath(root, yaml);
            try (var in = Files.newInputStream(yaml)) {
                var settings = new ObjectMapper(new YAMLFactory()).readTree(in);
                configuredAgent = settings == null ? null : settings.path("agent").path("name").textValue();
            } catch (JsonProcessingException malformed) {
                return false;
            }
        }
        // A shared workspace records only the last assistant initialized.
        return configuredAgent != null && !configuredAgent.isBlank();
    }

    static Map<String, Set<String>> forAgent(String agentName) throws IOException {
        var agent = AgentRegistry.get(agentName);
        if (agent == null) {
            return Map.of();
        }
        Set<String> skillWriters = AgentRegistry.names().stream()
                .filter(name -> agent.skillsDirectory().equals(AgentRegistry.get(name).skillsDirectory()))
                .collect(Collectors.toSet());
        Set<String> commandWriters = Set.of(agentName);
        Map<String, Set<String>> hashes = new HashMap<>();
        try (var in = ReleasedCatalog.class.getResourceAsStream("/workflow/pre-ledger-catalog.tsv")) {
            if (in == null) {
                throw new IOException("Missing pre-ledger catalog");
            }
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                boolean skill = fields[0].startsWith("skills/");
                Set<String> writers = skill ? skillWriters : commandWriters;
                if (fields[2].equals("*") || Arrays.stream(fields[2].split(",")).anyMatch(writers::contains)) {
                    int slash = fields[0].indexOf('/');
                    String dir = skill ? agent.skillsDirectory() : agent.commandDirectory();
                    hashes.computeIfAbsent(dir + fields[0].substring(slash), ignored -> new HashSet<>()).add(fields[1]);
                }
            }
        }
        return hashes;
    }
}
