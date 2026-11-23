package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.apache.camel.kit.config.AgentDescriptor;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.util.AnsiColors;
import org.apache.camel.kit.util.TemplateUtils;
import org.apache.camel.kit.workflow.WorkflowManifestLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class ClaudeGenerator extends DefaultGenerator {

    private final QuteTemplateEngine templateEngine = new QuteTemplateEngine();

    private static final String LEGACY_COMMAND_DIR = ".claude/commands";
    // These files were generated before Claude switched to native skills. New commands have never owned this path.
    private static final Set<String> LEGACY_COMMAND_STUBS = Set.of("camel-start.md", "camel-brainstorm.md",
            "camel-migrate.md", "camel-plan.md", "camel-execute.md", "camel-validate.md", "camel-debug.md",
            "camel-knowledge.md", "camel-ship.md");

    @Override
    public void preflight(InitContext ctx) throws Exception {
        super.preflight(ctx);
        Path current = ctx.projectDir();
        for (Path component : ctx.projectDir().relativize(ctx.projectDir().resolve(LEGACY_COMMAND_DIR))) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException(
                        "Initialization aborted: " + current + " is a symbolic link. Camel-Kit generates "
                                      + "and removes files under the agent directories and refuses to operate "
                                      + "through symbolic links; replace the link with a real directory and "
                                      + "re-run init.");
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
        }
    }

    @Override
    public void generate(InitContext ctx) throws Exception {
        super.generate(ctx);
        removeLegacyCommandStubs(ctx);
        generateClaudeMd(ctx);
        generateSettings(ctx);
        generateSubagents(ctx);
    }

    private void removeLegacyCommandStubs(InitContext ctx) throws IOException {
        Path legacyDir = ctx.projectDir().resolve(LEGACY_COMMAND_DIR);
        if (!Files.isDirectory(legacyDir)) {
            return;
        }
        try (var files = Files.list(legacyDir)) {
            for (Path file : files.filter(f -> LEGACY_COMMAND_STUBS.contains(f.getFileName().toString())).toList()) {
                GeneratedAssetCleaner.deleteRegularFile(ctx, file);
            }
        }
    }

    /** Installs the registry-declared project subagents, currently the read-only Ship worker. */
    private void generateSubagents(InitContext ctx) throws Exception {
        int subagents = 0;
        for (AgentDescriptor.TemplateInstall template : AgentRegistry.descriptor(ctx.agentName()).templates()) {
            if (template.target().startsWith(".claude/agents/")) {
                copyTemplateResource(ctx, template.source(), ctx.projectDir().resolve(template.target()));
                subagents++;
            }
        }
        if (subagents > 0) {
            ctx.printer().println(AnsiColors.green("✓") + " Generated " + subagents + " Claude Code subagents");
        }
    }

    private void generateClaudeMd(InitContext ctx) throws Exception {
        Map<String, Object> data = AgentsMdGenerator.templateData(ctx);
        String content = templateEngine.render("templates/claude/claude-md.md", data);
        ctx.files().write(ctx.projectDir().resolve("CLAUDE.md"), content);
    }

    private void generateSettings(InitContext ctx) throws Exception {
        Path settingsFile = ctx.projectDir().resolve(".claude/settings.json");
        var mapper = new ObjectMapper();
        var settings = mapper.readTree(TemplateUtils.readTemplate("templates/claude/settings.json"));
        var allow = (ArrayNode) settings.path("permissions").path("allow");
        WorkflowManifestLoader.loadDefault().mcpServers().forEach(server -> server.allowedTools()
                .forEach(tool -> allow.add("mcp__" + server.id() + "__" + tool)));
        if (ctx.files().write(settingsFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings))) {
            ctx.printer()
                    .println(AnsiColors.green("✓") + " Generated .claude/settings.json with auto-approved permissions");
        }
    }
}
