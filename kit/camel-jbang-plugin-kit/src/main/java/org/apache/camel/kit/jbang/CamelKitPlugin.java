package org.apache.camel.kit.jbang;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.CamelJBangPlugin;
import org.apache.camel.dsl.jbang.core.common.CommandTree;
import org.apache.camel.dsl.jbang.core.common.Plugin;
import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.command.DoctorCommand;
import org.apache.camel.kit.command.doc.DocCommand;
import org.apache.camel.kit.command.graph.GraphCommand;
import org.apache.camel.kit.command.pipeline.NextIdCommand;
import org.apache.camel.kit.command.plan.PlanCommand;
import org.apache.camel.kit.command.ship.ShipCommand;

/**
 * Camel JBang plugin for Camel Kit - Design Apache Camel Integrations with AI
 */
@CamelJBangPlugin(name = "camel-jbang-plugin-kit", firstVersion = "4.24.0")
public class CamelKitPlugin implements Plugin {

    @Override
    public void customize(CommandTree commandLine, CamelJBangMain main) {
        CamelKitMain camelKitMain = new CamelKitMain(new org.apache.camel.kit.output.SystemPrinter());
        camelKitMain.disableTui();
        CommandTree kit = new CommandTree(new KitCommand())
                .addSubcommand("init", new CommandTree(new KitInitCommand(main, camelKitMain)))
                .addSubcommand("doctor", new CommandTree(new DoctorCommand(camelKitMain)))
                .addSubcommand("doc", new CommandTree(new DocCommand()))
                .addSubcommand("graph", new CommandTree(new GraphCommand()))
                .addSubcommand("plan", new CommandTree(new PlanCommand()))
                .addSubcommand("nextId", new CommandTree(new NextIdCommand()))
                .addSubcommand("ship", new CommandTree(new ShipCommand()));
        commandLine.addSubcommand("kit", kit);
    }
}
