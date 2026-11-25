package org.apache.camel.kit.command.graph;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.HelpCommand;

import org.aesh.command.CommandDefinition;

@CommandDefinition(name = "graph",
                   description = "Project graph analysis, generation, and visualization",
                   groupCommands = {
                           GraphAnalyzeCommand.class, GraphStatsCommand.class, GraphFindCommand.class,
                           GraphNeighborsCommand.class,
                           GraphPathCommand.class, GraphSubgraphCommand.class, GraphRouteFlowCommand.class,
                           GraphImpactCommand.class, GraphRouteTopologyCommand.class, GraphDeadCodeCommand.class,
                           GraphProjectNormsCommand.class, GraphProjectContextCommand.class,
                           GraphRouteContextCommand.class,
                           GraphMigrationContextCommand.class,
                           GraphGenerateCommand.class, GraphVisualizeCommand.class,
                           HelpCommand.class
                   })
public class GraphCommand extends CliCommand {

    @Override
    public Integer call() {
        out.print(invocation.getHelpInfo());
        return 0;
    }
}
