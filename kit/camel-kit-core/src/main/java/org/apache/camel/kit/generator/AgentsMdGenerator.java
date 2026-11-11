package org.apache.camel.kit.generator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.camel.kit.util.AnsiColors;

class AgentsMdGenerator {

    private final QuteTemplateEngine qute = new QuteTemplateEngine();
    private final ManagedBlockMerger merger = new ManagedBlockMerger();

    void generate(InitContext ctx) throws Exception {
        Map<String, Object> data = new java.util.HashMap<>(
                Map.of(
                        "COMMAND_PREFIX", ctx.commandPrefix(),
                        "SKILLS_DIR", skillsDirFromProjectRoot(ctx),
                        "BOB2", "bob2".equals(ctx.agentName())));
        String content = qute.render("templates/shared/agents-md.md", data);

        // Always use managed block (even on first init) to enable preservation on reinit
        Path agentsMd = ctx.projectDir().resolve("AGENTS.md");
        boolean existed = Files.exists(agentsMd);
        merger.mergeBlock(agentsMd, "camel-kit-generated", content);

        if (existed) {
            ctx.printer().println(AnsiColors.green("✓") + " Updated AGENTS.md (preserved customizations)");
        } else {
            ctx.printer().println(AnsiColors.green("✓") + " Generated AGENTS.md with skill routing and iron laws");
        }
    }

    /** The skills directory as a project-root-relative path, so the agent can open files under it as written. */
    private static String skillsDirFromProjectRoot(InitContext ctx) {
        return ctx.projectDir().relativize(ctx.skillsDir()).toString().replace('\\', '/');
    }
}
