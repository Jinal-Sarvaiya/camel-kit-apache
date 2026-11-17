package org.apache.camel.kit.command.doc;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.HelpCommand;

import org.aesh.command.CommandDefinition;

@CommandDefinition(name = "doc",
                   description = "Document staleness and source-bound review evidence",
                   groupCommands = {
                           DocCheckCommand.class, DocInitCommand.class, DocStaleCommand.class, DocUnstaleCommand.class,
                           DocSnapshotCommand.class, DocRecordCommand.class,
                           HelpCommand.class
                   })
public class DocCommand extends CliCommand {

    @Override
    public Integer call() {
        out.print(invocation.getHelpInfo());
        return 0;
    }
}
