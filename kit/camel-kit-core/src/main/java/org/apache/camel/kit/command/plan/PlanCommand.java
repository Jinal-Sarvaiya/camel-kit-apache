package org.apache.camel.kit.command.plan;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.HelpCommand;

import org.aesh.command.CommandDefinition;

@CommandDefinition(name = "plan",
                   description = "Implementation plan analysis",
                   groupCommands = {PlanAnalyzeCommand.class, HelpCommand.class})
public class PlanCommand extends CliCommand {

    @Override
    public Integer call() {
        out.print(invocation.getHelpInfo());
        return 0;
    }
}
