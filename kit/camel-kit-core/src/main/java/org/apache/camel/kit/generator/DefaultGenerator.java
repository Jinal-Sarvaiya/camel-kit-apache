package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.camel.kit.output.Printer;
import org.apache.camel.kit.workflow.SkillCatalog;
import org.apache.camel.kit.workflow.WorkflowManifest;
import org.apache.camel.kit.workflow.WorkflowManifestLoader;

public class DefaultGenerator implements AgentGenerator {

    private final AgentsMdGenerator agentsMdGenerator;
    private final CommandStubGenerator commandStubGenerator;
    private final SkillResourceInstaller skillResourceInstaller;
    private final TraitApplicator traitApplicator;
    private final McpConfigGenerator mcpConfigGenerator;
    private final TemplateResourceCopier templateResourceCopier;

    public DefaultGenerator() {
        this(
             new AgentsMdGenerator(),
             new CommandStubGenerator(),
             new SkillResourceInstaller(),
             new TraitApplicator(),
             new McpConfigGenerator(),
             new TemplateResourceCopier());
    }

    DefaultGenerator(
                     AgentsMdGenerator agentsMdGenerator,
                     CommandStubGenerator commandStubGenerator,
                     SkillResourceInstaller skillResourceInstaller,
                     TraitApplicator traitApplicator,
                     McpConfigGenerator mcpConfigGenerator,
                     TemplateResourceCopier templateResourceCopier) {
        this.agentsMdGenerator = agentsMdGenerator;
        this.commandStubGenerator = commandStubGenerator;
        this.skillResourceInstaller = skillResourceInstaller;
        this.traitApplicator = traitApplicator;
        this.mcpConfigGenerator = mcpConfigGenerator;
        this.templateResourceCopier = templateResourceCopier;
    }

    @Override
    public void preflight(InitContext ctx) throws Exception {
        McpConfigGenerator.preflight(ctx);
    }

    @Override
    public void generate(InitContext ctx) throws Exception {
        WorkflowManifest workflow = WorkflowManifestLoader.loadDefault();
        ctx.files().cleanLegacyCatalogFiles(ctx);
        generateBaseAssets(ctx, workflow);
        beforeApplyTraits(ctx);
        applyTraits(ctx, workflow);
        generateMcpConfig(ctx, workflow);
    }

    protected void generateBaseAssets(InitContext ctx, WorkflowManifest workflow) throws Exception {
        Files.createDirectories(ctx.skillsDir());
        agentsMdGenerator.generate(ctx);
        installCatalog(ctx, workflow);
        new PersonaResourceInstaller().install(ctx);
    }

    private void installCatalog(InitContext ctx, WorkflowManifest workflow) throws Exception {
        if (ctx.agent().generatesCommandStubs()) {
            Files.createDirectories(ctx.commandsDir());
            commandStubGenerator.generate(ctx, workflow);
        }
        skillResourceInstaller.install(ctx, SkillCatalog.bundled());
    }

    /** Render final catalog bytes without writing to the project, including host traits and launcher spelling. */
    static Map<String, byte[]> previewCatalog(InitContext ctx, Set<String> paths) throws Exception {
        Path root = Files.createTempDirectory("camel-kit-preview-");
        try {
            Path skills = root.resolve(ctx.agent().skillsDirectory());
            Path commands = ctx.agent().generatesCommandStubs() ? root.resolve(ctx.agent().commandDirectory()) : skills;
            var preview = new InitContext(
                    ctx.agent(), ctx.agentName(), commands, skills, root, ctx.commandPrefix(),
                    ctx.distribution(), ctx.withCitrusMcp(), Printer.noop());
            var generator = new DefaultGenerator();
            var workflow = WorkflowManifestLoader.loadDefault();
            generator.installCatalog(preview, workflow);
            generator.applyTraits(preview, workflow);
            Map<String, byte[]> rendered = new HashMap<>();
            for (String path : paths) {
                rendered.put(path, Files.readAllBytes(root.resolve(path)));
            }
            return rendered;
        } finally {
            CatalogUpdate.deleteGeneratedTree(root, root);
        }
    }

    protected void beforeApplyTraits(InitContext ctx) throws Exception {
        // Extension hook for generators that need to prepare generated assets before trait application.
    }

    protected void applyTraits(InitContext ctx, WorkflowManifest workflow) throws Exception {
        traitApplicator.apply(ctx, workflow);
    }

    protected void generateMcpConfig(InitContext ctx, WorkflowManifest workflow) throws Exception {
        mcpConfigGenerator.generate(ctx, workflow);
    }

    /** Installs a template, preserving the target when it is a shared file owned by the user. */
    protected boolean copyTemplateResource(InitContext ctx, String resourcePath, Path target) throws IOException {
        return ctx.files().write(target, templateResourceCopier.read(resourcePath));
    }
}
