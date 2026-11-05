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

    private static final List<String> GLOBAL_CONFIG_FILES = List.of("opencode.json", "opencode.jsonc");

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
        boolean collisionDetected = false;
        StringBuilder denials = new StringBuilder();
        for (String server : mcpServers) {
            String normalized = normalizeServerName(server);
            if (BUILTIN_PERMISSION_PREFIXES.contains(normalized)) {
                collisionDetected = true;
            } else if (seen.add(normalized)) {
                denials.append("  ").append(normalized).append("_*: deny\n");
            }
        }
        data.put("MCP_TOOL_DENIALS", denials.toString());

        // OpenCode-specific: generate agent definitions
        generateAgents(ctx, data, !collisionDetected);
    }

    static String normalizeServerName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(isAsciiAlphanumericOrHyphen(c) ? c : '_');
        }
        return sb.toString();
    }

    private static boolean isAsciiAlphanumericOrHyphen(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
    }

    private List<String> readMcpServerNames(InitContext ctx) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Path configFile : OpenCodeProjectConfig.existingFiles(ctx.projectDir())) {
            collectMcpServersFromFile(configFile, names);
        }
        for (Path globalFile : globalConfigFiles()) {
            collectMcpServersFromFile(globalFile, names);
        }
        return List.copyOf(names);
    }

    private static void collectMcpServersFromFile(Path configFile, Set<String> names) throws IOException {
        JsonNode config = OpenCodeProjectConfig.newJsonMapper().readTree(configFile.toFile());
        if (config == null) {
            return;
        }
        JsonNode mcp = config.path("mcp");
        if (!mcp.isObject()) {
            return;
        }
        JsonNode servers = mcp.path("servers");
        boolean hasNativeContainer = servers.isObject() && !servers.has("type");
        if (hasNativeContainer) {
            servers.fields().forEachRemaining(entry -> {
                if (entry.getValue().isObject()) {
                    names.add(entry.getKey());
                }
            });
        }
        mcp.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject()
                    && !(hasNativeContainer && "servers".equals(entry.getKey()))) {
                names.add(entry.getKey());
            }
        });
    }

    static List<Path> globalConfigFiles() {
        Path globalDir = globalConfigDir();
        return GLOBAL_CONFIG_FILES.stream()
                .map(globalDir::resolve)
                .filter(Files::exists)
                .toList();
    }

    private static Path globalConfigDir() {
        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfigHome != null && !xdgConfigHome.isBlank()) {
            return Path.of(xdgConfigHome, "opencode");
        }
        return Path.of(System.getProperty("user.home"), ".config", "opencode");
    }

    private void generateAgents(InitContext ctx, Map<String, Object> data, boolean includeShipWorker)
            throws Exception {
        Path agentsDir = ctx.projectDir().resolve(".opencode/agents");
        Files.createDirectories(agentsDir);

        int count = 0;
        for (String agentName : AGENTS) {
            if ("camel-ship-worker".equals(agentName) && !includeShipWorker) {
                Files.deleteIfExists(agentsDir.resolve(agentName + ".md"));
                continue;
            }
            String content = templateEngine.render("templates/opencode/agents/" + agentName + ".md", data);
            Files.writeString(agentsDir.resolve(agentName + ".md"), content);
            count++;
        }

        ctx.printer().println(AnsiColors.green("✓") + " Generated " + count + " OpenCode agent definitions");
    }

}
