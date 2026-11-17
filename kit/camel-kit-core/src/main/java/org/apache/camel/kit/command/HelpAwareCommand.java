package org.apache.camel.kit.command;

import org.aesh.command.option.Option;

/** Opt-in help binding for commands with a public --help option. */
public abstract class HelpAwareCommand extends CliCommand {
    @Option(name = "help", shortName = 'h', hasValue = false, overrideRequired = true,
            parser = InformationOptionParser.class, description = "Display help and exit")
    protected boolean helpRequested;
}
