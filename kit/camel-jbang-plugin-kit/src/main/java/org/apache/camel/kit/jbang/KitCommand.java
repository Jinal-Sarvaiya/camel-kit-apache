package org.apache.camel.kit.jbang;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.InformationOptionParser;
import org.apache.camel.kit.command.KitOptionParser;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;

@CommandDefinition(name = "kit", description = "Design Apache Camel Integrations with AI", generateHelp = true)
public class KitCommand extends CliCommand {
    @org.aesh.command.option.Option(name = "help", shortName = 'h', hasValue = false, overrideRequired = true,
                                    parser = InformationOptionParser.class,
                                    description = "Display help and exit")
    protected boolean helpRequested;

    @Option(parser = KitOptionParser.class, name = "version", shortName = 'V',
            hasValue = false, description = "Print version information")
    private boolean versionRequested;

    @Override
    public Integer call() throws Exception {
        if (versionRequested) {
            for (String version : new KitVersionProvider().getVersion()) {
                out.println(version);
            }
        } else {
            out.print(invocation.getHelpInfo());
        }
        return 0;
    }
}
