package org.apache.camel.kit.generator;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.util.AnsiColors;
import org.apache.camel.kit.workflow.WorkflowManifest.WorkflowCommand;
import org.apache.camel.kit.workflow.WorkflowManifest.WorkflowSkill;
import org.apache.camel.kit.workflow.WorkflowManifestLoader;

class AgentsMdGenerator {

    private final QuteTemplateEngine qute = new QuteTemplateEngine();

    void generate(InitContext ctx) throws Exception {
        String content = qute.render("templates/shared/agents-md.md", templateData(ctx));
        if (ctx.files().write(ctx.projectDir().resolve("AGENTS.md"), content)) {
            ctx.printer().println(AnsiColors.green("✓") + " Generated AGENTS.md with skill routing and iron laws");
        }
    }

    static Map<String, Object> templateData(InitContext ctx) throws IOException {
        var workflow = WorkflowManifestLoader.loadDefault();
        List<WorkflowCommand> commands = workflow.commands().stream().filter(WorkflowCommand::userFacing).toList();
        List<String> direct
                = commands.stream().filter(c -> !"entry".equals(c.tier())).map(WorkflowCommand::name).toList();
        return new HashMap<>(
                Map.of(
                        "COMMAND_PREFIX", ctx.commandPrefix(),
                        "SKILLS_DIR", skillsDirFromProjectRoot(ctx),
                        "BOB2", "bob2".equals(ctx.agentName()),
                        "DIRECT_SKILLS", direct,
                        "UTILITY_SKILLS",
                        commands.stream().filter(c -> "utility".equals(c.tier())).map(WorkflowCommand::name).toList(),
                        "INTERNAL_SKILLS", workflow.skills().stream().filter(s -> "internal".equals(s.status()))
                                .map(WorkflowSkill::name).sorted().toList(),
                        "HAS_KNOWLEDGE", direct.contains("camel-knowledge"),
                        "HAS_SHIP", direct.contains("camel-ship")));
    }

    /** The skills directory as a project-root-relative path, so the agent can open files under it as written. */
    private static String skillsDirFromProjectRoot(InitContext ctx) {
        return ctx.projectDir().relativize(ctx.skillsDir()).toString().replace('\\', '/');
    }
}
