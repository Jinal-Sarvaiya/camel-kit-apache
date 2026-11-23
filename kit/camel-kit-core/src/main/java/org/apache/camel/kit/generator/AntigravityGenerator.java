package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.config.AgentDescriptor;
import org.apache.camel.kit.config.AgentRegistry;

/**
 * Installs Antigravity's native project skills, custom agents, and MCP configuration.
 */
public class AntigravityGenerator extends DefaultGenerator {

    @Override
    public void preflight(InitContext ctx) throws Exception {
        prepareCatalog(ctx, false);
    }

    @Override
    public Prepared prepareCatalog(InitContext ctx, boolean preview) throws Exception {
        try {
            GeneratedAssetPreflight.check(ctx);
        } catch (IOException e) {
            throw new InvalidAgentConfigurationException(e.getMessage(), e);
        }
        for (Path managed : List.of(ctx.projectDir().resolve("AGENTS.md"), ctx.projectDir().resolve(".agents"),
                ctx.projectDir().resolve("GEMINI.md"))) {
            if (Files.isSymbolicLink(managed)) {
                throw new InvalidAgentConfigurationException("Refusing to generate through symbolic link: " + managed);
            }
            if (Files.exists(managed)) {
                boolean directory = managed.getFileName().toString().equals(".agents");
                if (directory ? !Files.isDirectory(managed) : !Files.isRegularFile(managed)) {
                    throw new InvalidAgentConfigurationException(
                            "Antigravity requires " + managed + " to be a "
                                                                 + (directory ? "directory" : "regular file"));
                }
            }
        }
        requireType(ctx.skillsDir(), true);
        for (AgentDescriptor.TemplateInstall template : AgentRegistry.descriptor(ctx.agentName()).templates()) {
            Path target = ctx.projectDir().resolve(template.target());
            requireType(target, false);
            for (Path parent = target.getParent(); !parent.equals(ctx.projectDir()); parent = parent.getParent()) {
                requireType(parent, true);
            }
        }
        McpConfigGenerator.readAntigravityConfig(ctx.projectDir().resolve(ctx.agent().mcpConfigPath()));
        return new Prepared(ctx.files().planCatalog(ctx), this::writeWorkspace);
    }

    private void requireType(Path target, boolean directory) throws InvalidAgentConfigurationException {
        if (Files.exists(target) && (directory ? !Files.isDirectory(target) : !Files.isRegularFile(target))) {
            throw new InvalidAgentConfigurationException(
                    "Antigravity requires " + target + " to be a "
                                                         + (directory ? "directory" : "regular file"));
        }
    }

    @Override
    public void generate(InitContext ctx) throws Exception {
        preflight(ctx);
        writeWorkspace(ctx);
    }

    private void writeWorkspace(InitContext ctx) throws Exception {
        super.generate(ctx);
        QuteTemplateEngine templates = new QuteTemplateEngine();
        Map<String, Object> data = AgentsMdGenerator.templateData(ctx);
        for (AgentDescriptor.TemplateInstall template : AgentRegistry.descriptor(ctx.agentName()).templates()) {
            if (!PersonaResourceInstaller.isPersonaTemplate(template)) {
                Path target = ctx.projectDir().resolve(template.target());
                ctx.files().write(target, templates.render(template.source(), data));
            }
        }
        retireGeminiImports(ctx.projectDir());
    }

    private void retireGeminiImports(Path projectDir) throws Exception {
        // Antigravity also reads GEMINI.md. Retire only the old generated imports, preserving user rules.
        Path context = projectDir.resolve("GEMINI.md");
        if (Files.isRegularFile(context)) {
            String original = Files.readString(context);
            String migrated = original;
            for (String imported : List.of("skills/shared/context-authority.md", "instructions/iron-laws.md",
                    "instructions/mcp-usage.md", "instructions/pipeline-overview.md")) {
                migrated = migrated.replaceAll(
                        "(?m)^@\\.gemini/" + java.util.regex.Pattern.quote(imported) + "(?:\\R|$)",
                        "");
            }
            if (!original.equals(migrated)) {
                AtomicFileWriter.write(context, migrated);
            }
        }
    }
}
