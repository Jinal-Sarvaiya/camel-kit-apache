package io.github.luigidemasi.camelkit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.luigidemasi.camelkit.config.AgentDescriptor;
import io.github.luigidemasi.camelkit.config.AgentRegistry;
import io.github.luigidemasi.camelkit.util.AnsiColors;
import io.github.luigidemasi.camelkit.workflow.WorkflowManifestLoader;

public class ClaudeGenerator extends DefaultGenerator {

    private final QuteTemplateEngine templateEngine = new QuteTemplateEngine();

    private static final String LEGACY_COMMAND_DIR = ".claude/commands";

    @Override
    public void preflight(InitContext ctx) throws Exception {
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
        Set<String> ownedStubs = WorkflowManifestLoader.loadDefault().generatedCommandStubs().stream()
                .map(c -> c.name() + ".md")
                .collect(Collectors.toSet());
        try (var files = Files.list(legacyDir)) {
            for (Path file : files.filter(f -> ownedStubs.contains(f.getFileName().toString())).toList()) {
                GeneratedAssetCleaner.deleteRegularFile(ctx, file);
            }
        }
    }

    /** Installs the registry-declared project subagents, currently the read-only Ship worker. */
    private void generateSubagents(InitContext ctx) throws Exception {
        int subagents = 0;
        for (AgentDescriptor.TemplateInstall template : AgentRegistry.descriptor(ctx.agentName()).templates()) {
            if (template.target().startsWith(".claude/agents/")) {
                copyTemplateResource(template.source(), ctx.projectDir().resolve(template.target()));
                subagents++;
            }
        }
        if (subagents > 0) {
            ctx.printer().println(AnsiColors.green("✓") + " Generated " + subagents + " Claude Code subagents");
        }
    }

    private void generateClaudeMd(InitContext ctx) throws Exception {
        Map<String, Object> data = new HashMap<>(
                Map.of(
                        "COMMAND_PREFIX", ctx.commandPrefix()));
        String content = templateEngine.render("templates/claude/claude-md.md", data);
        Files.writeString(ctx.projectDir().resolve("CLAUDE.md"), content);
    }

    private void generateSettings(InitContext ctx) throws Exception {
        Path settingsFile = ctx.projectDir().resolve(".claude/settings.json");
        Files.createDirectories(settingsFile.getParent());
        copyTemplateResource("templates/claude/settings.json", settingsFile);
        ctx.printer()
                .println(AnsiColors.green("✓") + " Generated .claude/settings.json with auto-approved permissions");
    }
}
