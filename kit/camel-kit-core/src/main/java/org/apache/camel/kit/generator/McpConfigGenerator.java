package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.util.AnsiColors;
import org.apache.camel.kit.util.TemplateUtils;
import org.apache.camel.kit.workflow.WorkflowManifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class McpConfigGenerator {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper ANTIGRAVITY_MAPPER
            = org.apache.camel.kit.config.OpenCodeProjectConfig.newJsonMapper();

    void generate(InitContext ctx, WorkflowManifest workflow) throws InvalidAgentConfigurationException {
        try {
            Path configFile = ctx.projectDir().resolve(ctx.agent().mcpConfigPath());
            if (configFile.getParent() != null) {
                Files.createDirectories(configFile.getParent());
            }

            QuteTemplateEngine qute = new QuteTemplateEngine();
            String template = TemplateUtils.readTemplate(ctx.agent().mcpConfigTemplatePath());
            String processed
                    = qute.renderString(template, templateData(ctx.distribution(), workflow, ctx.withCitrusMcp()));
            if ("toml".equals(ctx.agent().mcpConfigFormat())) {
                new CodexConfigMerger().merge(configFile, processed, ctx.withCitrusMcp());
            } else if ("opencode".equals(ctx.agentName())) {
                new OpenCodeConfigMerger().merge(configFile, processed, ctx.withCitrusMcp());
            } else {
                // Merge into an existing config so servers and settings the project already has are preserved.
                String key = ctx.agent().mcpServerContainerKey();
                ObjectNode config = readJsonConfig(configFile, ctx.agent().name(), key);
                ObjectNode generated = (ObjectNode) JSON_MAPPER.readTree(processed).path(key);
                if (!Files.exists(configFile)) {
                    AtomicFileWriter.write(configFile, processed);
                } else {
                    boolean needsUpdate = !generated.properties().stream()
                            .allMatch(server -> server.getValue()
                                    .equals(config.path(key).get(server.getKey())));
                    boolean needsCitrusRemoval = !ctx.withCitrusMcp()
                            && config.path(key).has("citrus");
                    if (needsUpdate || needsCitrusRemoval) {
                        String original = Files.readString(configFile);
                        String bom = original.startsWith("\uFEFF") ? "\uFEFF" : "";
                        JsoncObjectEditor editor = new JsoncObjectEditor(original.substring(bom.length()));
                        if (!config.has(key)) {
                            editor.upsertRootMember(key, generated);
                        } else {
                            editor.removeObjectMembers(key, generated.fieldNames());
                            if (needsCitrusRemoval) {
                                editor.removeObjectMembers(key,
                                        java.util.Collections.singletonList("citrus").iterator());
                            }
                            editor.appendObjectMembers(key, generated);
                        }
                        AtomicFileWriter.write(configFile, bom + editor.content());
                    }
                }
            }

            ctx.printer().println(AnsiColors.green("✓") + " MCP config created for " + ctx.agent().name());
        } catch (InvalidAgentConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create MCP config: " + e.getMessage(), e);
        }
    }

    static ObjectNode readAntigravityConfig(Path configFile) throws InvalidAgentConfigurationException {
        return readJsonConfig(configFile, "Antigravity", "mcpServers");
    }

    /**
     * Validates the JSON MCP config a generator will merge into, so an unusable file fails before anything is written.
     */
    static void preflight(InitContext ctx) throws InvalidAgentConfigurationException {
        if (!"toml".equals(ctx.agent().mcpConfigFormat()) && !"opencode".equals(ctx.agentName())) {
            readJsonConfig(ctx.projectDir().resolve(ctx.agent().mcpConfigPath()), ctx.agent().name(),
                    ctx.agent().mcpServerContainerKey());
        }
    }

    static ObjectNode readJsonConfig(Path configFile, String agent, String key)
            throws InvalidAgentConfigurationException {
        if (!Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) {
            return JSON_MAPPER.createObjectNode();
        }
        if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidAgentConfigurationException(
                    agent + " MCP config must be a regular file: " + configFile);
        }
        try {
            JsonNode config = ANTIGRAVITY_MAPPER.readTree(configFile.toFile());
            if (config == null || !config.isObject()
                    || (config.has(key) && !config.path(key).isObject())) {
                throw new InvalidAgentConfigurationException(
                        agent + " MCP config must contain a JSON object with an " + key + " object: " + configFile);
            }
            return (ObjectNode) config;
        } catch (IOException e) {
            throw new InvalidAgentConfigurationException(
                    "Invalid " + agent + " MCP config " + configFile + ": " + e.getMessage()
                                                         + ". Fix JSON syntax and remove duplicate keys before retrying.",
                    e);
        }
    }

    private Map<String, Object> templateData(
            DistributionConfig dist, WorkflowManifest workflow, boolean withCitrusMcp)
            throws IOException {
        Map<String, Object> data = new java.util.HashMap<>(
                VersionPlaceholderResolver.buildVersionTemplateData(dist));

        WorkflowManifest.WorkflowMcpServer camelServer = workflow.mcpServer("camel");
        data.put("CAMEL_TOOLS_JSON", toJsonArray(camelServer.allowedTools()));

        WorkflowManifest.WorkflowMcpServer knowledgeServer = workflow.mcpServer("camel-knowledge");
        data.put("KNOWLEDGE_TOOLS_JSON", toJsonArray(knowledgeServer.allowedTools()));
        data.put("KNOWLEDGE_DESCRIPTION", knowledgeServer.description());

        data.put("CITRUS_ENABLED", withCitrusMcp);
        WorkflowManifest.WorkflowMcpServer citrusServer = workflow.mcpServer("citrus");
        data.put("CITRUS_TOOLS_JSON", toJsonArray(citrusServer.allowedTools()));
        data.put("CITRUS_DESCRIPTION", citrusServer.description());
        return data;
    }

    private static String toJsonArray(List<String> values) throws IOException {
        return JSON_MAPPER.writeValueAsString(values);
    }
}
