package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.apache.camel.kit.config.AgentDescriptor;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.util.AnsiColors;

/**
 * Generates repository-scoped OpenAI Codex assets.
 */
public class CodexGenerator extends DefaultGenerator {

    private static final String AGENTS_TEMPLATE = "templates/codex/agents-md.md";

    private final QuteTemplateEngine templateEngine = new QuteTemplateEngine();

    @Override
    public void generate(InitContext ctx) throws Exception {
        rejectSymlinkedManagedPaths(ctx);
        super.generate(ctx);
        installDescriptorTemplates(ctx);
    }

    private void rejectSymlinkedManagedPaths(InitContext ctx) {
        try {
            GeneratedAssetPreflight.check(ctx);
        } catch (IOException e) {
            throw new IllegalStateException("Refusing to generate Codex assets: " + e.getMessage(), e);
        }
    }

    private void installDescriptorTemplates(InitContext ctx) throws Exception {
        AgentDescriptor descriptor = AgentRegistry.descriptor(ctx.agentName());
        if (descriptor == null) {
            throw new IllegalArgumentException("Unsupported agent: " + ctx.agentName());
        }

        int customAgents = 0;
        for (AgentDescriptor.TemplateInstall template : descriptor.templates()) {
            if (PersonaResourceInstaller.isPersonaTemplate(template)) {
                continue;
            }
            Path target = ctx.projectDir().resolve(template.target());
            if (AGENTS_TEMPLATE.equals(template.source())) {
                AtomicFileWriter.write(target, templateEngine.render(
                        template.source(), Map.of("COMMAND_PREFIX", ctx.commandPrefix())));
            } else {
                copyTemplateResource(template.source(), target);
            }
            if (template.target().startsWith(".codex/agents/") && template.target().endsWith(".toml")) {
                customAgents++;
            }
        }

        ctx.printer().println(AnsiColors.green("✓") + " Generated " + customAgents + " Codex custom agents");
    }
}
