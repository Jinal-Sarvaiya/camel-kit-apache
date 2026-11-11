package org.apache.camel.kit.generator;

import org.apache.camel.kit.config.AgentDescriptor;
import org.apache.camel.kit.config.AgentRegistry;

public class Bob2Generator extends DefaultGenerator {

    @Override
    public void generate(InitContext ctx) throws Exception {
        GeneratedAssetCleaner.deleteRegularFile(ctx, ctx.projectDir().resolve(".bob/rules-camel-ship/ship.md"));
        BobGeneratedAssetCleaner.deleteLegacyModeRules(ctx);
        BobGeneratedAssetCleaner.deleteBob1Assets(ctx);
        super.generate(ctx);
        generateRegisteredResources(ctx);
    }

    private void generateRegisteredResources(InitContext ctx) throws Exception {
        for (AgentDescriptor.TemplateInstall template : AgentRegistry.descriptor("bob2").templates()) {
            if (!PersonaResourceInstaller.isPersonaTemplate(template)) {
                copyTemplateResource(ctx, template.source(), ctx.projectDir().resolve(template.target()));
            }
        }
    }

}
