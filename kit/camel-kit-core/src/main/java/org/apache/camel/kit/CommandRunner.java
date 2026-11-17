package org.apache.camel.kit;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;

import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.Command;
import org.aesh.command.CommandRuntime;
import org.aesh.command.HelpFeature;
import org.aesh.command.container.CommandContainer;
import org.aesh.command.impl.container.AeshCommandContainer;
import org.aesh.command.impl.container.AeshCommandContainerBuilder;
import org.aesh.command.impl.parser.CommandLineParser;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;
import org.aesh.command.impl.shell.StdoutShell;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.parser.CommandLineParserException;
import org.aesh.command.parser.OptionParserException;
import org.aesh.command.parser.RequiredOptionException;
import org.aesh.command.validator.CommandValidatorException;
import org.aesh.command.validator.OptionValidatorException;

/** One-shot argv execution. The runtime owns no interactive terminal. */
public final class CommandRunner {
    private final Command<CommandInvocation> command;
    private final Map<String, CommandRunner> children = new LinkedHashMap<>();
    private PrintWriter out = new PrintWriter(System.out, true, Charset.defaultCharset());
    private PrintWriter err = new PrintWriter(System.err, true, Charset.defaultCharset());
    private boolean argumentFiles;
    private CommandLineParser<CommandInvocation> parser;
    private CommandContainer<CommandInvocation> container;

    public CommandRunner(Command<CommandInvocation> command) {
        this.command = command;
    }

    public CommandRunner addSubcommand(String name, Command<CommandInvocation> child) {
        return addSubcommand(name, new CommandRunner(child));
    }

    public CommandRunner addSubcommand(String name, CommandRunner child) {
        children.put(name, child);
        container = null;
        parser = null;
        return this;
    }

    public CommandRunner argumentFiles(boolean enabled) {
        argumentFiles = enabled;
        return this;
    }

    public CommandRunner setOut(PrintWriter out) {
        this.out = out;
        return this;
    }

    public CommandRunner setErr(PrintWriter err) {
        this.err = err;
        return this;
    }

    public CommandLineParser<CommandInvocation> parser() {
        if (parser == null) {
            container();
        }
        return parser;
    }

    private CommandContainer<CommandInvocation> container() {
        if (container != null) {
            configure(parser, out, err);
            return container;
        }
        try {
            var builder = new AeshCommandContainerBuilder<CommandInvocation>();
            builder.setHelpDefaults(EnumSet.of(HelpFeature.FAIL_ON_DUPLICATE_OPTIONS));
            container = builder.create(command);
            for (var entry : children.entrySet()) {
                var child = entry.getValue().container();
                child.getParser().getProcessedCommand().setName(entry.getKey());
                ((AeshCommandContainer<CommandInvocation>) container).addChild(child);
            }
            parser = container.getParser();
            parser.updateAnsiMode(false);
            configure(parser, out, err);
            return container;
        } catch (CommandLineParserException e) {
            throw new IllegalStateException("Invalid command definition", e);
        }
    }

    private static void configure(
            CommandLineParser<CommandInvocation> parser,
            PrintWriter out, PrintWriter err) {
        if (parser.getCommand() instanceof CliCommand cli) {
            cli.configureOutput(out, err);
        }
        for (var child : parser.getAllChildParsers()) {
            configure(child, out, err);
        }
    }

    private CommandRuntime<CommandInvocation> runtime() {
        var registry = AeshCommandRegistryBuilder.<CommandInvocation>builder().command(container()).create();
        return AeshCommandRuntimeBuilder.<CommandInvocation>builder()
                .commandRegistry(registry).argumentFiles(false).ansiColors(false)
                .shell(new StdoutShell() {
                    @Override
                    public void write(String value, boolean paging) {
                        out.print(value);
                        out.flush();
                    }

                    @Override
                    public void writeln(String value, boolean paging) {
                        out.println(value);
                        out.flush();
                    }
                }).build();
    }

    public void parse(String... args) throws Exception {
        var runtime = runtime();
        try {
            for (var execution : runtime.buildExecutor(parser.getProcessedCommand().name(), expand(args))
                    .getExecutions()) {
                execution.populateCommand();
            }
        } finally {
            runtime.close();
        }
    }

    public int execute(String... args) {
        var runtime = runtime();
        try {
            var executions = runtime.buildExecutor(parser.getProcessedCommand().name(), expand(args)).getExecutions();
            var execution = executions.get(0);
            try {
                execution.populateCommand();
            } catch (CommandLineParserException e) {
                if (!rootInformationRequested() || !onlyInformationalErrors(parser)) {
                    throw e;
                }
            }
            if (rootInformationRequested()) {
                if (optionGiven("help")) {
                    out.print(parser.printHelp());
                } else {
                    for (String version : new VersionProvider().getVersion()) {
                        out.println(version);
                    }
                }
                return 0;
            }
            return execution.execute().getResultValue();
        } catch (IOException | CommandLineParserException | OptionValidatorException | CommandValidatorException e) {
            err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            e.printStackTrace(err);
            return 1;
        } finally {
            runtime.close();
            out.flush();
            err.flush();
        }
    }

    private String[] expand(String[] args) throws IOException {
        return argumentFiles ? ArgumentFiles.expand(args) : args;
    }

    private boolean rootInformationRequested() {
        return command instanceof CamelKitMain && (optionGiven("help") || optionGiven("version"));
    }

    private boolean optionGiven(String name) {
        return !parser.getProcessedCommand().findLongOptionNoActivatorCheck(name).getValues().isEmpty();
    }

    // Root information flags excuse unknown tokens and omitted required arguments, but never malformed options.
    private static boolean onlyInformationalErrors(CommandLineParser<?> parser) {
        // Kit parsers give malformed options a distinct type. Other parsers may use the generic type for them.
        boolean kitOptions = parser.getProcessedCommand().getOptions().stream()
                .allMatch(option -> option.parser() instanceof KitOptionParser);
        for (var error : parser.getProcessedCommand().parserExceptions()) {
            if (!(error instanceof RequiredOptionException)
                    && !(kitOptions && error.getClass() == OptionParserException.class)) {
                return false;
            }
        }
        return parser.getAllChildParsers().stream().filter(child -> child.parsedCommand() != null)
                .allMatch(CommandRunner::onlyInformationalErrors);
    }
}
