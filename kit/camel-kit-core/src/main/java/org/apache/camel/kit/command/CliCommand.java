package org.apache.camel.kit.command;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.ParentCommand;

/** Shared execution and output contract for standalone and hosted commands. */
public abstract class CliCommand implements Command<CommandInvocation>, Callable<Integer> {
    protected PrintWriter out = new PrintWriter(System.out, true, java.nio.charset.Charset.defaultCharset());
    protected PrintWriter err = new PrintWriter(System.err, true, java.nio.charset.Charset.defaultCharset());
    protected CommandInvocation invocation;
    @ParentCommand
    protected Command<CommandInvocation> parent;

    public void configureOutput(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    protected String commandName() {
        String name = nameOf(this);
        return parent == null ? name : qualifiedName(parent) + " " + name;
    }

    protected static String qualifiedName(Command<?> command) {
        return command instanceof CliCommand cli ? cli.commandName() : nameOf(command);
    }

    private static String nameOf(Object command) {
        for (Class<?> type = command.getClass(); type != null; type = type.getSuperclass()) {
            CommandDefinition definition = type.getAnnotation(CommandDefinition.class);
            if (definition != null) {
                return definition.name();
            }
        }
        throw new IllegalStateException("Missing command definition");
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        this.invocation = invocation;
        try {
            return CommandResult.valueOf(call());
        } catch (UsageException e) {
            err.println(e.getMessage());
            return CommandResult.USAGE_ERROR;
        } catch (Exception e) {
            e.printStackTrace(err);
            return CommandResult.FAILURE;
        } finally {
            out.flush();
            err.flush();
        }
    }

    public static final class UsageException extends IllegalArgumentException {
        public UsageException(String message) {
            super(message);
        }
    }
}
