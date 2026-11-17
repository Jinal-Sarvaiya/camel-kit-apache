package org.apache.camel.kit.jbang;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.command.InitCommand;

import org.aesh.command.CommandDefinition;

/** Uses the core option binding and initialization behavior inside the Camel host. */
@CommandDefinition(name = "init", description = "Initialize a new Camel-Kit project", generateHelp = true)
public class KitInitCommand extends InitCommand {
    public KitInitCommand(CamelJBangMain main) {
        this(main, new CamelKitMain(new org.apache.camel.kit.output.SystemPrinter()));
    }

    public KitInitCommand(CamelJBangMain main, CamelKitMain camelKitMain) {
        super(camelKitMain);
        camelKitMain.disableTui();
    }
}
