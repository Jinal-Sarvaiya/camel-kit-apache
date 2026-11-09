package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern SUBSTITUTION_PATTERN = Pattern.compile("\\{(env|file):[^}]+}");

    private static final List<String> GLOBAL_CONFIG_FILES = List.of("opencode.json", "opencode.jsonc");

    private static final List<String> ANCESTOR_CONFIG_FILES = List.of(
            "opencode.json", "opencode.jsonc",
            ".opencode/opencode.json", ".opencode/opencode.jsonc");

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

        McpInventory inventory = readMcpInventory(ctx);
        Set<String> seen = new LinkedHashSet<>();
        boolean collisionDetected = false;
        StringBuilder denials = new StringBuilder();
        for (String server : inventory.servers) {
            if (SUBSTITUTION_PATTERN.matcher(server).find()) {
                inventory = new McpInventory(inventory.servers, true);
                continue;
            }
            String normalized = normalizeServerName(server);
            if (BUILTIN_PERMISSION_PREFIXES.contains(normalized)) {
                collisionDetected = true;
            } else if (seen.add(normalized)) {
                denials.append("  ").append(normalized).append("_*: deny\n");
            }
        }
        data.put("MCP_TOOL_DENIALS", denials.toString());

        // OpenCode-specific: generate agent definitions
        generateAgents(ctx, data, !collisionDetected && !inventory.hasUnresolvedSubstitution);
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

    record McpInventory(List<String> servers, boolean hasUnresolvedSubstitution) {
    }

    private McpInventory readMcpInventory(InitContext ctx) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        boolean hasSubstitution = false;
        for (Path configFile : OpenCodeProjectConfig.existingFiles(ctx.projectDir())) {
            hasSubstitution |= collectFromFileWithSubstitutionCheck(configFile, names);
        }
        for (Path ancestorFile : ancestorConfigFiles(ctx.projectDir())) {
            hasSubstitution |= collectFromFileWithSubstitutionCheck(ancestorFile, names);
        }
        for (Path globalFile : globalConfigFiles()) {
            hasSubstitution |= collectFromFileWithSubstitutionCheck(globalFile, names);
        }
        hasSubstitution |= collectFromDirWithSubstitutionCheck(customConfigDir(), names);
        hasSubstitution |= collectFromDirWithSubstitutionCheck(managedConfigDir(), names);
        Path explicitConfig = explicitConfigFile();
        if (explicitConfig != null) {
            hasSubstitution |= collectFromFileWithSubstitutionCheck(explicitConfig, names);
        }
        String inlineContent = inlineConfigContent();
        if (inlineContent != null && !inlineContent.isBlank()) {
            if (SUBSTITUTION_PATTERN.matcher(inlineContent).find()) {
                hasSubstitution = true;
            }
            collectMcpServersFromContent(inlineContent, names);
        }
        return new McpInventory(List.copyOf(names), hasSubstitution);
    }

    private static boolean collectFromFileWithSubstitutionCheck(Path configFile, Set<String> names)
            throws IOException {
        String rawContent = Files.readString(configFile);
        if (rawContent.startsWith("\uFEFF")) {
            rawContent = rawContent.substring(1);
        }
        boolean hasSubstitution = SUBSTITUTION_PATTERN.matcher(rawContent).find();
        collectMcpServersFromContent(rawContent, names);
        return hasSubstitution;
    }

    private static boolean collectFromDirWithSubstitutionCheck(Path dir, Set<String> names)
            throws IOException {
        if (dir == null) {
            return false;
        }
        boolean hasSubstitution = false;
        for (String fileName : GLOBAL_CONFIG_FILES) {
            Path candidate = dir.resolve(fileName);
            if (Files.exists(candidate)) {
                hasSubstitution |= collectFromFileWithSubstitutionCheck(candidate, names);
            }
        }
        return hasSubstitution;
    }

    static void collectMcpServersFromContent(String jsonContent, Set<String> names) throws IOException {
        collectMcpServersFromNode(
                OpenCodeProjectConfig.newJsonMapper().readTree(jsonContent), names);
    }

    private static void collectMcpServersFromNode(JsonNode config, Set<String> names) {
        if (config == null) {
            return;
        }
        JsonNode mcp = config.path("mcp");
        if (!mcp.isObject()) {
            return;
        }
        JsonNode servers = mcp.path("servers");
        boolean hasNativeContainer = servers.isObject() && isNativeServersContainer(servers);
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

    private static boolean isNativeServersContainer(JsonNode servers) {
        JsonNode type = servers.path("type");
        return !type.isTextual();
    }

    static List<Path> ancestorConfigFiles(Path projectDir) throws IOException {
        Path realProject = projectDir.toRealPath();
        Path parent = realProject.getParent();
        List<Path> result = new java.util.ArrayList<>();
        while (parent != null) {
            for (String name : ANCESTOR_CONFIG_FILES) {
                Path candidate = parent.resolve(name);
                if (Files.exists(candidate)) {
                    result.add(candidate);
                }
            }
            parent = parent.getParent();
        }
        return result;
    }

    List<Path> globalConfigFiles() {
        Path globalDir = globalConfigDir();
        return GLOBAL_CONFIG_FILES.stream()
                .map(globalDir::resolve)
                .filter(Files::exists)
                .toList();
    }

    Path globalConfigDir() {
        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfigHome != null && !xdgConfigHome.isBlank()) {
            return Path.of(xdgConfigHome, "opencode");
        }
        return Path.of(System.getProperty("user.home"), ".config", "opencode");
    }

    Path explicitConfigFile() {
        String explicitPath = System.getenv("OPENCODE_CONFIG");
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path path = Path.of(explicitPath);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    Path customConfigDir() {
        String dir = System.getenv("OPENCODE_CONFIG_DIR");
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir);
        }
        return null;
    }

    Path managedConfigDir() {
        return managedConfigDirForOs(System.getProperty("os.name", ""));
    }

    static Path managedConfigDirForOs(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of("/Library/Application Support/opencode");
        } else if (os.contains("win")) {
            return Path.of("C:\\ProgramData", "opencode");
        }
        return Path.of("/etc/opencode");
    }

    String inlineConfigContent() {
        return System.getenv("OPENCODE_CONFIG_CONTENT");
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
