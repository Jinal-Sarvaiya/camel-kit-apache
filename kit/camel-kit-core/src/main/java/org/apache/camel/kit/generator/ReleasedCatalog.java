package org.apache.camel.kit.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.util.ConfinedPaths;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Known installed bytes from before init recorded ownership, scoped to the project's previous assistant. */
final class ReleasedCatalog {
    private ReleasedCatalog() {
    }

    static Map<String, Set<String>> forWorkspace(Path root, String agentName) throws IOException {
        if (agentName == null) {
            return Map.of();
        }
        Path config = root.resolve(".camel-kit/config.properties");
        ConfinedPaths.requirePath(root, config);
        String previous;
        if (Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            var settings = new Properties();
            try (var in = Files.newBufferedReader(config)) {
                settings.load(in);
            } catch (IllegalArgumentException malformed) {
                return Map.of();
            }
            previous = settings.getProperty("agent.name");
        } else {
            Path yaml = root.resolve(".camel-kit/config.yaml");
            if (!Files.isRegularFile(yaml, LinkOption.NOFOLLOW_LINKS)) {
                return Map.of();
            }
            ConfinedPaths.requirePath(root, yaml);
            try (var in = Files.newInputStream(yaml)) {
                var settings = new ObjectMapper(new YAMLFactory()).readTree(in);
                previous = settings == null ? null : settings.path("agent").path("name").asText();
            } catch (JsonProcessingException malformed) {
                return Map.of();
            }
        }
        if (!agentName.equals(previous) && !("bob2".equals(agentName) && "bob".equals(previous))) {
            return Map.of();
        }
        var agent = AgentRegistry.get(agentName);
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
                if (fields[2].equals("*") || Set.of(fields[2].split(",")).contains(agentName)) {
                    int slash = fields[0].indexOf('/');
                    String dir = fields[0].startsWith("skills/") ? agent.skillsDirectory() : agent.commandDirectory();
                    hashes.computeIfAbsent(dir + fields[0].substring(slash), ignored -> new HashSet<>()).add(fields[1]);
                }
            }
        }
        return hashes;
    }
}
