package org.apache.camel.kit.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.kit.graph.model.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class GraphSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String FORMAT_VERSION = "1.1";

    private GraphSerializer() {
    }

    public static void write(ProjectGraph graph, Path file, String projectRoot) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("generatedAt", Instant.now().toString());
        root.put("projectRoot", projectRoot);

        ObjectNode stats = root.putObject("stats");
        stats.put("nodes", graph.nodeCount());
        stats.put("edges", graph.edgeCount());

        ObjectNode nodesObj = root.putObject("nodes");
        for (GraphNode node : graph.getNodes().values()) {
            requireId(node.id(), "node ID");
            writeRecord(nodesObj.putObject(node.id()), node.type(), node.properties(), "node '" + node.id() + "'");
        }

        ArrayNode edgesArr = root.putArray("edges");
        for (GraphEdge edge : graph.getEdges()) {
            ObjectNode edgeObj = edgesArr.addObject();
            edgeObj.put("from", requireId(edge.from(), "edge source"));
            edgeObj.put("to", requireId(edge.to(), "edge target"));
            writeRecord(edgeObj, edge.type(), edge.properties(),
                    "edge from '" + edge.from() + "' to '" + edge.to() + "'");
        }

        MAPPER.writeValue(file.toFile(), root);
    }

    private static void writeRecord(ObjectNode record, GraphType type, Map<String, String> properties, String location)
            throws IOException {
        if (type == null || properties == null) {
            throw new IOException("Malformed graph: " + location + " requires a type and properties map");
        }
        record.put("type", type.name());
        if (!properties.isEmpty()) {
            ObjectNode props = record.putObject("properties");
            for (var entry : properties.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IOException(
                            "Malformed graph: " + location + " properties require string keys and values");
                }
                props.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static String requireId(String id, String location) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IOException("Malformed graph: blank " + location);
        }
        return id;
    }

    public static ProjectGraph read(Path file) throws IOException {
        return read(file, new GraphTypeRegistry(Map.of()));
    }

    public static ProjectGraph read(Path file, GraphTypeRegistry types) throws IOException {
        ProjectGraph graph = new ProjectGraph();
        JsonNode root = MAPPER.readTree(file.toFile());
        String version = requiredText(root, "version", "root");
        if (!FORMAT_VERSION.equals(version) && !"1.0".equals(version)) {
            throw new IOException(
                    "Unsupported graph format version '" + version + "'; expected 1.0 or " + FORMAT_VERSION);
        }

        JsonNode nodesObj = requiredObject(root, "nodes", "root");
        for (var entry : nodesObj.properties()) {
            String id = requireId(entry.getKey(), "node ID");
            String location = "node '" + id + "'";
            JsonNode node = entry.getValue();
            String type = requiredText(node, "type", location);
            try {
                graph.addNode(new GraphNode(id, types.nodeType(type), properties(node, location)));
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed graph: " + location + ": " + e.getMessage(), e);
            }
        }

        JsonNode edges = requiredArray(root, "edges", "root");
        int index = 0;
        for (JsonNode edge : edges) {
            String location = "edge[" + index++ + "]";
            String from = requiredText(edge, "from", location);
            String to = requiredText(edge, "to", location);
            String type = requiredText(edge, "type", location);
            try {
                graph.addEdge(new GraphEdge(from, to, types.edgeType(type), properties(edge, location)));
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed graph: " + location + ": " + e.getMessage(), e);
            }
        }

        return graph;
    }

    private static JsonNode requiredObject(JsonNode parent, String field, String location) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            throw new IOException("Malformed graph: " + location + " is missing object field '" + field + "'");
        }
        return node;
    }

    private static JsonNode requiredArray(JsonNode parent, String field, String location) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw new IOException("Malformed graph: " + location + " is missing array field '" + field + "'");
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String field, String location) throws IOException {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IOException("Malformed graph: " + location + " is missing text field '" + field + "'");
        }
        return node.asText();
    }

    private static Map<String, String> properties(JsonNode record, String location) throws IOException {
        if (!record.has("properties")) {
            return Map.of();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (var entry : requiredObject(record, "properties", location).properties()) {
            if (!entry.getValue().isTextual()) {
                throw new IOException(
                        "Malformed graph: " + location + " property '" + entry.getKey() + "' must be a string");
            }
            properties.put(entry.getKey(), entry.getValue().textValue());
        }
        return properties;
    }
}
