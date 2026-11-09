package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
        // Refuse to operate through a symlinked agents path before anything can be deleted or written.
        requireRealShipWorkerPath(ctx);
        try {
            new OpenCodeConfigMerger().validateExisting(ctx.projectDir().resolve(ctx.agent().mcpConfigPath()));
        } catch (Exception e) {
            // OpenCode may still load a project config that Kit rejects (duplicate keys, or {env:}/{file:}
            // substitutions that only become valid JSON after host substitution). Initialization aborts here, so
            // the installed worker's denial list can no longer be shown to match the effective MCP server set:
            // remove it. The user's configuration is left untouched.
            removeShipWorker(ctx);
            throw e;
        }
    }

    @Override
    public void generate(InitContext ctx) throws Exception {
        // Invalidate the worker before project-config merging can abort; it is regenerated below only if the
        // effective MCP inventory is bounded.
        removeShipWorker(ctx);

        // Run default generation (commands, skills, MCP config)
        super.generate(ctx);

        Map<String, Object> data = new HashMap<>(
                Map.of(
                        "COMMAND_PREFIX", ctx.commandPrefix()));

        McpInventory inventory = readMcpInventory(ctx);
        Set<String> seen = new LinkedHashSet<>();
        boolean collisionDetected = false;
        boolean unbounded = inventory.unbounded;
        StringBuilder denials = new StringBuilder();
        for (String server : inventory.servers) {
            if (SUBSTITUTION_PATTERN.matcher(server).find()) {
                unbounded = true;
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
        generateAgents(ctx, data, !collisionDetected && !unbounded);
    }

    private static final String[] SHIP_WORKER_PATH = {".opencode", "agents", "camel-ship-worker.md"};

    /**
     * Fails when any component of the Ship worker path below the project is a symbolic link, so that cleanup and
     * generation never operate outside the selected workspace.
     */
    private static void requireRealShipWorkerPath(InitContext ctx) throws IOException {
        Path current = ctx.projectDir();
        for (String component : SHIP_WORKER_PATH) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException(
                        "Initialization aborted: " + current + " is a symbolic link. Camel-Kit generates "
                                      + "and removes files under the agent directories and refuses to operate "
                                      + "through symbolic links; replace the link with a real directory and "
                                      + "re-run init.");
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
        }
    }

    private static void removeShipWorker(InitContext ctx) throws IOException {
        requireRealShipWorkerPath(ctx);
        Files.deleteIfExists(ctx.projectDir().resolve(".opencode/agents/camel-ship-worker.md"));
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

    /**
     * @param servers   MCP server names found in the effective host configuration
     * @param unbounded true when the effective MCP inventory cannot be established (unresolved substitutions,
     *                  unparsable sources, or sources that are not inspected); the native Ship worker must not be
     *                  generated
     */
    record McpInventory(List<String> servers, boolean unbounded) {
    }

    private McpInventory readMcpInventory(InitContext ctx) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        boolean unbounded = false;
        for (Path configFile : OpenCodeProjectConfig.existingFiles(ctx.projectDir())) {
            unbounded |= collectFromFile(configFile, names);
        }
        for (Path ancestorFile : ancestorConfigFiles(ctx.projectDir())) {
            unbounded |= collectFromFile(ancestorFile, names);
        }
        for (Path globalFile : globalConfigFiles()) {
            unbounded |= collectFromFile(globalFile, names);
        }
        unbounded |= collectFromDir(customConfigDir(), names);
        unbounded |= collectFromDir(managedConfigDir(), names);
        unbounded |= managedPreferencesPresent();
        Path explicitConfig = explicitConfigFile();
        if (explicitConfig != null) {
            unbounded |= collectFromFile(explicitConfig, names);
        }
        String inlineContent = inlineConfigContent();
        if (inlineContent != null && !inlineContent.isBlank()) {
            unbounded |= collectFromContent(inlineContent, names);
        }
        return new McpInventory(List.copyOf(names), unbounded);
    }

    private static boolean collectFromFile(Path configFile, Set<String> names) throws IOException {
        String rawContent = Files.readString(configFile);
        if (rawContent.startsWith("\uFEFF")) {
            rawContent = rawContent.substring(1);
        }
        return collectFromContent(rawContent, names);
    }

    /**
     * Collects server names from raw config text. OpenCode substitutes {env:}/{file:} references in the whole config
     * text before parsing, so raw text containing a substitution cannot be inventoried reliably (the substituted text
     * may even change the JSON structure). Such content, and content that cannot be parsed, is reported as unbounded
     * without being parsed so that a parse error can never abort generation before the stale worker is removed.
     *
     * @return true when the inventory is unbounded because of this content
     */
    private static boolean collectFromContent(String rawContent, Set<String> names) {
        if (SUBSTITUTION_PATTERN.matcher(rawContent).find()) {
            return true;
        }
        try {
            collectMcpServersFromContent(rawContent, names);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean collectFromDir(Path dir, Set<String> names) throws IOException {
        if (dir == null) {
            return false;
        }
        boolean unbounded = false;
        for (String fileName : GLOBAL_CONFIG_FILES) {
            Path candidate = dir.resolve(fileName);
            if (Files.exists(candidate)) {
                unbounded |= collectFromFile(candidate, names);
            }
        }
        return unbounded;
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
        return managedConfigDirForOs(osName(), programDataEnv());
    }

    String programDataEnv() {
        return System.getenv("ProgramData");
    }

    static Path managedConfigDirForOs(String osName) {
        return managedConfigDirForOs(osName, null);
    }

    static Path managedConfigDirForOs(String osName, String programData) {
        if (isMac(osName)) {
            return Path.of("/Library/Application Support/opencode");
        } else if (isWindows(osName)) {
            String base = programData != null && !programData.isBlank() ? programData : "C:\\ProgramData";
            return Path.of(base, "opencode");
        }
        return Path.of("/etc/opencode");
    }

    String osName() {
        return System.getProperty("os.name", "");
    }

    String userName() {
        return System.getProperty("user.name", "");
    }

    Path managedPreferencesRoot() {
        return Path.of("/Library/Managed Preferences");
    }

    /**
     * OpenCode on macOS also loads MDM managed-preference plists (ai.opencode.managed.plist), per-user first. These are
     * not parsed here, so their presence means the effective MCP inventory cannot be bounded.
     */
    private boolean managedPreferencesPresent() {
        if (!isMac(osName())) {
            return false;
        }
        Path root = managedPreferencesRoot();
        return Files.exists(root.resolve("ai.opencode.managed.plist"))
                || (!userName().isBlank()
                        && Files.exists(root.resolve(userName()).resolve("ai.opencode.managed.plist")));
    }

    private static boolean isMac(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win") && !isMac(osName);
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
            AtomicFileWriter.write(agentsDir.resolve(agentName + ".md"), content);
            count++;
        }

        ctx.printer().println(AnsiColors.green("✓") + " Generated " + count + " OpenCode agent definitions");
    }

}
