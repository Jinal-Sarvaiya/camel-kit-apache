package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.util.ConfinedPaths;
import org.apache.camel.kit.workflow.WorkflowManifestLoader;

/** Checks only generated agent paths, allowing unrelated worktrees and custom assets. */
public final class GeneratedAssetPreflight {
    private GeneratedAssetPreflight() {
    }

    public static void check(InitContext ctx) throws IOException {
        var workflow = WorkflowManifestLoader.loadDefault();
        Set<Path> outputs = new LinkedHashSet<>();
        outputs.add(ctx.projectDir().resolve("AGENTS.md"));
        outputs.add(ctx.skillsDir());
        outputs.add(ctx.projectDir().resolve(ctx.agent().mcpConfigPath()));
        for (var command : workflow.generatedCommandStubs()) {
            if (ctx.agent().generatesCommandStubs() || command.isSkillOnly(ctx.agentName())) {
                outputs.add(ctx.commandsDir().resolve(command.name() + "." + ctx.agent().fileFormat()));
            }
        }
        for (var template : AgentRegistry.descriptor(ctx.agentName()).templates()) {
            outputs.add(ctx.projectDir().resolve(template.target()));
        }
        for (Path output : outputs) {
            ConfinedPaths.requirePath(ctx.projectDir(), output);
        }
        ConfinedPaths.requireTree(ctx.projectDir(), ctx.skillsDir().resolve("shared"));
        for (var skill : workflow.skills()) {
            ConfinedPaths.requireTree(ctx.projectDir(), ctx.skillsDir().resolve(skill.name()));
        }
    }
}
