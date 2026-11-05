package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.kit.config.OpenCodeProjectConfig;
import org.apache.camel.kit.util.AnsiColors;

import com.fasterxml.jackson.databind.JsonNode;

public class OpenCodeGenerator extends DefaultGenerator {

    private static final String[] AGENTS = {
            "brainstormer", "planner", "implementer",
            "validator", "tester", "migrator", "executor",
            "researcher", "reviewer", "camel-ship-worker"
    };

    private static final Set<String> BUILTIN_PERMISSION_PREFIXES = Set.of("external");

    private final QuteTemplateEngine templateEngine = new QuteTemplateEngine();

    @Override
    public void preflight(InitContext ctx) throws Exception {
        new OpenCodeConfigMerger().validateExisting(ctx.projectDir().resolve(ctx.agent().mcpConfigPath()));
    }

    @Override
    public void generate(InitContext ctx) throws Exception {
        // Run default generation (commands, skills, MCP config)
        super.generate(ctx);

        Map<String, Object> data = new HashMap<>(
                Map.of(
                        "COMMAND_PREFIX", ctx.commandPrefix()));

        List<String> mcpServers = readMcpServerNames(ctx);
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder denials = new StringBuilder();
        for (String server : mcpServers) {
            String normalized = normalizeServerName(server);
            if (BUILTIN_PERMISSION_PREFIXES.contains(normalized)) {
                continue;
            }
            if (seen.add(normalized)) {
                denials.append("  ").append(normalized).append("_*: deny\n");
            }
        }
        data.put("MCP_TOOL_DENIALS", denials.toString());

        // OpenCode-specific: generate agent definitions
        generateAgents(ctx, data);
    }

    static String normalizeServerName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    private List<String> readMcpServerNames(InitContext ctx) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Path configFile : OpenCodeProjectConfig.existingFiles(ctx.projectDir())) {
            JsonNode config = OpenCodeProjectConfig.newJsonMapper().readTree(configFile.toFile());
            if (config == null) {
                continue;
            }
            JsonNode mcp = config.path("mcp");
            if (!mcp.isObject()) {
                continue;
            }
            mcp.fieldNames().forEachRemaining(names::add);
        }
        return List.copyOf(names);
    }

    private void generateAgents(InitContext ctx, Map<String, Object> data) throws Exception {
        Path agentsDir = ctx.projectDir().resolve(".opencode/agents");
        Files.createDirectories(agentsDir);

        for (String agentName : AGENTS) {
            String content = templateEngine.render("templates/opencode/agents/" + agentName + ".md", data);
            Files.writeString(agentsDir.resolve(agentName + ".md"), content);
        }

        ctx.printer().println(AnsiColors.green("✓") + " Generated " + AGENTS.length + " OpenCode agent definitions");
    }

}
