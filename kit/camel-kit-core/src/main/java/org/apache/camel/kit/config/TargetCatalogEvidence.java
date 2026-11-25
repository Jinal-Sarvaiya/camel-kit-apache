package org.apache.camel.kit.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Validates bounded MCP evidence captured by the calling workflow. */
public final class TargetCatalogEvidence {
    private TargetCatalogEvidence() {
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Check the captured outputs of the two version-sensitive MCP calls made with this profile. */
    public static void check(TargetRuntimeProfile profile, byte[] bytes) throws IOException {
        String expectedVersion = profile.requiredCatalogVersion();
        JsonNode evidence = JSON.readTree(bytes);
        if (evidence == null || !JSON.valueToTree(profile).equals(evidence.path("profile"))) {
            throw new IOException("Catalog evidence belongs to a different or missing target profile");
        }
        JsonNode list = toolResult(profile.catalog(), evidence.path("list"), "camel_catalog_components");
        if (!profile.camelVersion().equals(list.path("camelVersion").textValue())) {
            throw new IOException(
                    "Expected Camel catalog " + profile.camelVersion() + ", received " + list.path("camelVersion"));
        }
        JsonNode components = list.path("components");
        if (!components.isArray() || !list.path("count").isInt() || list.get("count").intValue() != components.size()
                || components.size() > 2) {
            throw new IOException("Invalid component list for the timer catalog check");
        }
        boolean timer = false;
        for (JsonNode component : components) {
            timer |= "timer".equals(component.path("name").textValue());
        }
        JsonNode component = toolResult(profile.catalog(), evidence.path("maven"), "camel_catalog_component_maven");
        String artifact = switch (profile.runtime()) {
            case "quarkus" -> "org.apache.camel.quarkus:camel-quarkus-timer";
            case "spring-boot" -> "org.apache.camel.springboot:camel-timer-starter";
            default -> "org.apache.camel:camel-timer";
        };
        if (!timer || !"timer".equals(component.path("name").textValue())
                || !artifact.equals(component.path("groupId").asText() + ":" + component.path("artifactId").asText())
                || !expectedVersion.equals(component.path("version").textValue())) {
            throw new IOException(
                    "Expected the timer component from " + artifact + ":" + expectedVersion);
        }
    }

    private static JsonNode toolResult(TargetRuntimeProfile.CatalogRequest catalog, JsonNode call, String tool)
            throws IOException {
        ObjectNode expected = JSON.valueToTree(catalog);
        if ("camel_catalog_components".equals(tool)) {
            expected.put("filter", "timer").put("label", "").put("limit", 2);
        } else {
            expected.put("component", "timer");
        }
        if (!tool.equals(call.path("name").textValue()) || !expected.equals(call.path("arguments"))) {
            throw new IOException("Missing or mismatched catalog request for " + tool);
        }
        JsonNode result = call.path("result");
        JsonNode content = result.path("content");
        if ((result.has("isError") && !result.path("isError").equals(BooleanNode.FALSE)) || !content.isArray()
                || content.size() != 1
                || !"text".equals(content.get(0).path("type").textValue())
                || !content.get(0).path("text").isTextual()) {
            throw new IOException("Missing or unsuccessful catalog response");
        }
        JsonNode data = JSON.readTree(content.get(0).get("text").textValue());
        if (data == null || !data.isObject()) {
            throw new IOException("Missing catalog response fields");
        }
        return data;
    }

}
