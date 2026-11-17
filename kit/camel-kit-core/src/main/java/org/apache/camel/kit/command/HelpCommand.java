package org.apache.camel.kit.command;

import java.util.List;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Arguments;

@CommandDefinition(name = "help", description = "Display help for a command", generateHelp = true)
public final class HelpCommand extends CliCommand {
    @Arguments
    List<String> commands = List.of();

    @Override
    public Integer call() {
        String path = qualifiedName(parent) + (commands.isEmpty() ? "" : " " + commands.get(0));
        String help = invocation.getHelpInfo(path);
        if (help == null || (!commands.isEmpty() && java.util.Arrays.stream(
                parent.getClass().getAnnotation(CommandDefinition.class).groupCommands())
                .noneMatch(type -> type.getAnnotation(CommandDefinition.class).name().equals(commands.get(0))))) {
            throw new UsageException("Unknown command: " + path);
        }
        out.print(help);
        return 0;
    }
}
