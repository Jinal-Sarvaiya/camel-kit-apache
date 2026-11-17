package org.apache.camel.kit.command.doc;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "doc",
         description = "Document staleness and source-bound review evidence",
         subcommands = {
                 DocCheckCommand.class, DocInitCommand.class, DocStaleCommand.class, DocUnstaleCommand.class,
                 DocSnapshotCommand.class, DocRecordCommand.class,
                 CommandLine.HelpCommand.class
         })
public class DocCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
