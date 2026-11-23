package org.apache.camel.kit.command;

import java.nio.file.Path;
import java.util.Objects;

import org.apache.camel.kit.workflow.SkillCatalog;
import org.apache.camel.kit.workflow.WorkflowHookRunner;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;

@CommandDefinition(name = "hook", generateHelp = true,
                   description = "Invoke a bundled workflow hook or verify its skill result")
public class HookCommand extends HelpAwareCommand {
    @Option(parser = KitOptionParser.class, required = true, description = "Declared step contribution ID")
    String id;

    @Option(parser = KitOptionParser.class, required = true, converter = PathConverter.class,
            description = "Caller-owned JSON with sources, target, artifacts and optional confirmed answer")
    Path input;

    @Option(parser = KitOptionParser.class, converter = PathConverter.class,
            description = "JSON result from the requested skill")
    Path result;

    @Option(parser = KitOptionParser.class, converter = PathConverter.class,
            description = "Prior needs-input JSON for the confirmed answer")
    Path resume;

    @Override
    public Integer call() {
        if (helpRequested) {
            out.print(invocation.getHelpInfo());
            return 0;
        }
        try {
            var outcome = new WorkflowHookRunner(SkillCatalog.bundled(), Path.of(".")).run(id, input, result, resume);
            out.println(WorkflowHookRunner.toJson(outcome));
            return outcome.status().equals("passed") ? 0 : 1;
        } catch (Exception | LinkageError e) {
            err.println(id + ": " + Objects.toString(e.getMessage(), e.toString()));
            return 1;
        }
    }
}
