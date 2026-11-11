package org.apache.camel.kit.graph.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class ConfigParser implements GraphParser {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Override
    public void parse(Path projectRoot, ProjectGraph graph) {
        parseFiles(projectRoot, scannedFilePaths(projectRoot, GraphParser.projectFiles(projectRoot)), graph);
    }

    @Override
    public List<String> scannedFiles(Path projectRoot) {
        return GraphParser.findFiles(projectRoot, this::isConfigFile);
    }

    @Override
    public List<Path> scannedFilePaths(Path projectRoot, List<Path> projectFiles) {
        return GraphParser.findFilePaths(projectRoot, projectFiles, this::isConfigFile);
    }

    @Override
    public void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph) {
        GraphParser.requireSafeFiles(projectRoot, files);
        for (Path file : files) {
            if (isYaml(file.getFileName().toString())) {
                parseYaml(file, projectRoot, graph);
            } else {
                parseProperties(file, projectRoot, graph);
            }
        }
    }

    private boolean isConfigFile(Path file) {
        String name = file.getFileName().toString();
        return name.equals("application.properties") || name.matches("application-.*\\.properties")
                || name.matches("application(-.*)?\\.ya?ml");
    }

    private static boolean isYaml(String name) {
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /**
     * Reads {@code application.yml} / {@code application.yaml} (and profile variants) and records every scalar as a
     * {@code CONFIG_PROPERTY} using the same dotted key a properties file would use, so downstream consumers do not
     * care which format defines a key. Lists use {@code key[0]} indexes.
     */
    private void parseYaml(Path file, Path projectRoot, ProjectGraph graph) {
        String relativePath = projectRoot.relativize(file).toString();
        String filenameProfile = extractProfile(file.getFileName().toString());
        int docIndex = 0;
        try (var documents = yamlMapper.readerFor(JsonNode.class).readValues(file.toFile())) {
            while (documents.hasNextValue()) {
                JsonNode document = (JsonNode) documents.nextValue();
                Map<String, String> flattened = new LinkedHashMap<>();

                // Check for document-level profile (spring.config.activate.on-profile)
                String documentProfile = extractDocumentProfile(document);

                flatten("", document, flattened);

                // Determine effective profile (document > filename)
                String effectiveProfile = documentProfile != null ? documentProfile : filenameProfile;

                // Make docIndex effectively final for lambda
                final int currentDocIndex = docIndex;
                flattened.forEach((key, value) -> {
                    Map<String, String> nodeProps = new HashMap<>();
                    nodeProps.put("key", key);
                    nodeProps.put("value", value);
                    nodeProps.put("file", relativePath);
                    nodeProps.put("documentIndex", String.valueOf(currentDocIndex));
                    if (effectiveProfile != null) {
                        nodeProps.put("profile", effectiveProfile);
                    }
                    // The node ID is "config:<key>" like every other config source, so the default value stays reachable
                    // by key. Only a document restricted by spring.config.activate.on-profile gets a profile-qualified
                    // ID, so its value does not overwrite the default document's.
                    String nodeId = "config:" + key + (documentProfile != null ? ":profile=" + documentProfile : "");
                    graph.addNode(new GraphNode(nodeId, NodeType.CONFIG_PROPERTY, nodeProps));
                });
                docIndex++;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse YAML configuration: " + file, e);
        }
    }

    /**
     * Extract profile from document content (spring.config.activate.on-profile).
     */
    private String extractDocumentProfile(JsonNode document) {
        if (document.has("spring")) {
            JsonNode spring = document.get("spring");
            if (spring.has("config")) {
                JsonNode config = spring.get("config");
                if (config.has("activate")) {
                    JsonNode activate = config.get("activate");
                    if (activate.has("on-profile")) {
                        return activate.get("on-profile").asText();
                    }
                }
            }
        }
        return null;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(
                    entry -> flatten(prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(),
                            entry.getValue(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else if (!prefix.isEmpty()) {
            out.put(prefix, node.asText());
        }
    }

    private void parseProperties(Path file, Path projectRoot, ProjectGraph graph) {
        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(GraphParser.readSource(file))) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse properties: " + file, e);
        }

        String relativePath = projectRoot.relativize(file).toString();
        String profile = extractProfile(file.getFileName().toString());

        props.forEach((key, value) -> {
            String keyStr = key.toString();
            String nodeId = "config:" + keyStr;
            Map<String, String> nodeProps = new HashMap<>();
            nodeProps.put("key", keyStr);
            nodeProps.put("value", value.toString());
            nodeProps.put("file", relativePath);
            if (profile != null) {
                nodeProps.put("profile", profile);
            }
            graph.addNode(new GraphNode(nodeId, NodeType.CONFIG_PROPERTY, nodeProps));
        });
    }

    private String extractProfile(String fileName) {
        if (!fileName.startsWith("application-")) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > "application-".length() ? fileName.substring("application-".length(), dot) : null;
    }
}
