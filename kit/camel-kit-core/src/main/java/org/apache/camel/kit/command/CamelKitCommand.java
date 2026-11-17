package org.apache.camel.kit.command;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.output.Printer;
import org.apache.camel.kit.util.AnsiColors;

/**
 * Base class for all Camel-Kit commands (following Camel JBang CamelCommand pattern).
 */
public abstract class CamelKitCommand extends CliCommand {
    @org.aesh.command.option.Option(name = "help", shortName = 'h', hasValue = false, overrideRequired = true,
                                    parser = InformationOptionParser.class,
                                    description = "Display help and exit")
    protected boolean helpRequested;

    protected final CamelKitMain main;

    protected CamelKitCommand(CamelKitMain main) {
        this.main = main;
    }

    protected Printer printer() {
        return main.getOut();
    }

    @Override
    public Integer call() throws Exception {
        return doCall();
    }

    /**
     * Implement command logic in subclasses.
     */
    public abstract Integer doCall() throws Exception;

    // Convenience methods for colored output
    protected String green(String s) {
        return AnsiColors.green(s);
    }

    protected String red(String s) {
        return AnsiColors.red(s);
    }

    protected String yellow(String s) {
        return AnsiColors.yellow(s);
    }

    protected String cyan(String s) {
        return AnsiColors.cyan(s);
    }

    protected String bold(String s) {
        return AnsiColors.bold(s);
    }

    protected String dim(String s) {
        return AnsiColors.dim(s);
    }
}
