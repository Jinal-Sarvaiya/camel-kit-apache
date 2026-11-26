package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.util.AnsiColors;
import org.apache.camel.kit.util.TemplateUtils;
import org.apache.camel.kit.workflow.WorkflowManifest;
import org.apache.camel.kit.workflow.WorkflowManifestLoader;

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

            String processed = render(ctx, workflow);
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
     * Validates the MCP config a generator will merge into, so an unusable file fails before anything is written. Reads
     * only, so a preview runs the same checks. OpenCode validates its own configuration files.
     */
    static void preflight(InitContext ctx) throws IOException {
        if ("toml".equals(ctx.agent().mcpConfigFormat())) {
            // The merge itself is checked, with the block initialization will write, not only the existing file.
            new CodexConfigMerger().validate(
                    ctx.projectDir().resolve(ctx.agent().mcpConfigPath()),
                    render(ctx, WorkflowManifestLoader.loadDefault()), ctx.withCitrusMcp());
        } else if (!"opencode".equals(ctx.agentName())) {
            readJsonConfig(ctx.projectDir().resolve(ctx.agent().mcpConfigPath()), ctx.agent().name(),
                    ctx.agent().mcpServerContainerKey());
        }
    }

    /** The MCP configuration Camel-Kit generates for this agent, before it is merged into an existing file. */
    private static String render(InitContext ctx, WorkflowManifest workflow) throws IOException {
        String template = TemplateUtils.readTemplate(ctx.agent().mcpConfigTemplatePath());
        return new QuteTemplateEngine()
                .renderString(template, templateData(ctx, workflow));
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

    private static Map<String, Object> templateData(InitContext ctx, WorkflowManifest workflow)
            throws IOException {
        Map<String, Object> data = new java.util.HashMap<>(
                VersionPlaceholderResolver.buildVersionTemplateData(ctx.distribution()));

        WorkflowManifest.WorkflowMcpServer camelServer = workflow.mcpServer("camel");
        data.put("CAMEL_TOOLS_JSON", toJsonArray(camelServer.allowedTools()));

        WorkflowManifest.WorkflowMcpServer knowledgeServer = workflow.mcpServer("camel-knowledge");
        data.put("KNOWLEDGE_TOOLS_JSON", toJsonArray(knowledgeServer.allowedTools()));
        data.put("KNOWLEDGE_DESCRIPTION", knowledgeServer.description());

        data.put("CITRUS_ENABLED", ctx.withCitrusMcp());
        WorkflowManifest.WorkflowMcpServer citrusServer = workflow.mcpServer("citrus");
        data.put("CITRUS_TOOLS_JSON", toJsonArray(citrusServer.allowedTools()));
        data.put("CITRUS_DESCRIPTION", citrusServer.description());

        // OpenCode-specific: generate exact MCP tool permissions from workflow manifest
        if ("opencode".equals(ctx.agentName())) {
            data.put("OPENCODE_MCP_PERMISSIONS", buildOpenCodePermissions(workflow, ctx.withCitrusMcp()));
        }

        return data;
    }

    private static String buildOpenCodePermissions(WorkflowManifest workflow, boolean withCitrusMcp)
            throws IOException {
        java.util.LinkedHashMap<String, String> permissions = new java.util.LinkedHashMap<>();

        // Add wildcard fallbacks first (OpenCode matches rules in order)
        permissions.put("camel_*", "ask");
        permissions.put("camel-knowledge_*", "ask");
        if (withCitrusMcp) {
            permissions.put("citrus_*", "ask");
        }

        // Add exact permissions for each tool from workflow manifest (after wildcards for precedence)
        WorkflowManifest.WorkflowMcpServer camelServer = workflow.mcpServer("camel");
        for (String tool : camelServer.allowedTools()) {
            permissions.put("camel_" + tool, "allow");
        }

        WorkflowManifest.WorkflowMcpServer knowledgeServer = workflow.mcpServer("camel-knowledge");
        for (String tool : knowledgeServer.allowedTools()) {
            permissions.put("camel-knowledge_" + tool, "allow");
        }

        if (withCitrusMcp) {
            WorkflowManifest.WorkflowMcpServer citrusServer = workflow.mcpServer("citrus");
            for (String tool : citrusServer.allowedTools()) {
                permissions.put("citrus_" + tool, "allow");
            }
        }

        return JSON_MAPPER.writeValueAsString(permissions);
    }

    private static String toJsonArray(List<String> values) throws IOException {
        return JSON_MAPPER.writeValueAsString(values);
    }
}
