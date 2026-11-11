package org.apache.camel.kit.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.config.AgentGeneratorStrategy;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.generator.InvalidAgentConfigurationException;
import org.apache.camel.kit.generator.ManagedFiles;
import org.apache.camel.kit.output.Printer;
import org.apache.camel.kit.service.InitGraphSummary;
import org.apache.camel.kit.service.InitProgress;
import org.apache.camel.kit.service.InitReporter;
import org.apache.camel.kit.service.InitRequest;
import org.apache.camel.kit.service.InitResult;
import org.apache.camel.kit.service.InitService;
import org.apache.camel.kit.service.InitWarning;
import org.apache.camel.kit.tui.InitTuiView;
import org.apache.camel.kit.tui.TaskTracker;
import org.apache.camel.kit.util.PrerequisiteChecker;

import dev.tamboui.image.capability.TerminalImageCapabilities;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Initialize a new Camel-Kit project.
 */
@Command(name = "init", description = "Initialize a new Camel-Kit project")
public class InitCommand extends CamelKitCommand {

    @Parameters(index = "0", description = "Project name", arity = "0..1")
    public String projectName;

    @Option(names = {"-a", "--ai"},
            description = "AI agent: bob2 (IBM Bob 2, default), antigravity, "
                          + "claude, codex, copilot, pi, qwen, opencode",
            defaultValue = "bob2")
    public String ai;

    @Option(names = {"--here"}, description = "Initialize in current directory")
    public boolean here;

    @Option(names = {"--citrus-version"},
            description = "Citrus version for test schemas",
            defaultValue = "default")
    public String citrusVersion;

    @Option(names = {"--with-citrus-mcp"},
            description = "Include the Citrus MCP server in agent configuration (default: off)")
    public boolean withCitrusMcp;

    @Option(names = {"--no-fetch"}, description = "Skip external catalog fetching")
    public boolean noFetch;

    @Option(names = {"--force"},
            description = "Reinitialize a project that already has .camel-kit/. Files you changed are preserved")
    public boolean force;

    @Option(names = {"--dry-run"},
            description = "Show what init would do to files the project may already own, without writing anything")
    public boolean dryRun;

    @Option(names = {"--replace"}, paramLabel = "<path>",
            description = "Replace this existing shared file (for example AGENTS.md or mvnw) instead of "
                          + "preserving it (repeatable)")
    public List<String> replace;
    @Option(names = {"--replace-wrapper"}, description = "Replace Maven wrapper even if it exists")
    public boolean replaceWrapper;

    @Option(names = {"--record-baseline"},
            description = "For an existing project, run its tests once to record a baseline result "
                          + "(executes the project's build and tests; may take several minutes)")
    public boolean recordBaseline;

    @Option(names = {"--silent"}, description = "Suppress all output (no banner, no progress, no summary)")
    public boolean silent;

    @Option(names = {"-c", "--config"},
            description = "Path to config properties file (default: ~/.camel-kit/config.properties)")
    public Path configFile;

    @Option(names = {"-p", "--property"}, arity = "1..*",
            description = "Override config property: -p key=value (repeatable)")
    public List<String> properties;

    @Option(names = {"--source-platform"},
            description = "Source platform for migration graph analysis: mulesoft, camel, biztalk, auto (default: auto)")
    public String sourcePlatform;

    private final InitService initService;
    private Path resolvedTargetDir;

    public InitCommand(CamelKitMain main) {
        this(main, new InitService());
    }

    InitCommand(CamelKitMain main, InitService initService) {
        super(main);
        this.initService = initService;
    }

    @Override
    public Integer doCall() throws Exception {
        if (silent) {
            main.setOut(Printer.noop());
        }

        if (configFile != null || properties != null) {
            CamelKitMain.reloadDistribution(configFile, properties);
        }

        if (!AgentRegistry.contains(ai)) {
            if (!silent)
                main.printBanner();
            printer().println(red("Error: " + AgentRegistry.unsupportedAgentMessage(ai)));
            printer().println("Available agents: " + String.join(", ", AgentRegistry.names()));
            return 1;
        }

        if (projectName == null && !here) {
            if (!silent)
                main.printBanner();
            printer().println(red("Error: Please provide a project name or use --here"));
            return 1;
        }

        if (here) {
            resolvedTargetDir = Path.of("").toAbsolutePath().normalize();
            projectName = resolvedTargetDir.getFileName().toString();
        } else {
            resolvedTargetDir = Path.of(projectName).toAbsolutePath().normalize();
        }

        // Only Camel-Kit's own directory marks a project as initialized: an existing application may well have its
        // own AGENTS.md, which init preserves.
        Path camelKitDir = resolvedTargetDir.resolve(".camel-kit");
        if (dryRun) {
            return doDryRun();
        }
        if (!force && Files.isDirectory(camelKitDir)) {
            if (!silent)
                main.printBanner();
            printer().println();
            printer().println(yellow("\u26A0") + bold(" Project already initialized"));
            printer().println(dim("  Directory: ") + cyan(resolvedTargetDir.toString()));
            printer().println(dim("  Found:     ") + ".camel-kit/");
            printer().println();
            printer().println(dim("  To reinitialize: ") + bold("--force") + dim(" (files you changed are preserved)"));
            printer().println(dim("  Example:      ") + "camel-kit init " + projectName + " --ai " + ai + " --force");
            printer().println();
            return 1;
        }

        if (!silent) {
            if (main.isTuiEnabled() && TerminalImageCapabilities.detect().supportsNativeImages()) {
                InitTuiView tui = new InitTuiView();
                Printer original = main.getOut();
                main.setOut(tui.createPrinter());
                main.setTaskTracker(tui.createTaskTracker());
                main.closeTerminal();
                try {
                    return tui.run(this::doInitWork);
                } catch (Throwable e) {
                    // InitTuiView.run only throws during setup, before any init work starts,
                    // so falling back to plain output cannot double-run the init.
                    main.setOut(original);
                    main.setTaskTracker(TaskTracker.noop());
                    printer().println(yellow("Warning: TUI unavailable (" + e.getMessage()
                                             + ") — continuing without it"));
                }
            }

            main.printBanner();
        }

        return doInitWork();
    }

    private Integer doDryRun() throws Exception {
        final List<ManagedFiles.Change> changes;
        try {
            changes = initService.preview(initRequest());
        } catch (InvalidAgentConfigurationException | IllegalArgumentException e) {
            printer().println(red("Error: " + e.getMessage()));
            return 1;
        }
        printer()
                .println(bold("Dry run") + dim(" \u2014 nothing was written to ") + cyan(resolvedTargetDir.toString()));
        for (ManagedFiles.Change change : changes) {
            String outcome = switch (change.action()) {
                case CREATE -> green("create  ");
                case REFRESH -> green("replace ");
                case MERGE -> green("merge   ");
                case PRESERVE -> yellow("preserve");
            };
            printer().println("  " + outcome + "  " + change.path());
        }
        printer().println(dim("  merge: Camel Kit's block or servers are added and your content is kept."));
        printer().println(dim("  preserve: the file is yours and stays as it is; replace it with --replace <path>."));
        printer().println(dim("  Camel Kit's own assets (skills, subagents, .camel-kit/) are always written."));
        return 0;
    }

    private Integer doInitWork() throws Exception {
        if (!silent) {
            PrerequisiteChecker.check(printer());
        }

        final InitResult result;
        try {
            result = initService.initialize(initRequest());
        } catch (InvalidAgentConfigurationException | IllegalArgumentException e) {
            printer().println(red("Error: " + e.getMessage()));
            return 1;
        }
        printSummary(result.projectName(), result.agent().name(), result.citrusSchemaCount());
        printConflicts(result);
        printNextSteps(result.projectName(), result.agent().name(), result.agentName());
        return 0;
    }

    private InitRequest initRequest() {
        return new InitRequest(
                projectName,
                ai,
                resolvedTargetDir,
                citrusVersion,
                noFetch,
                withCitrusMcp,
                sourcePlatform,
                detectCommandPrefix(),
                CamelKitMain.DEFAULT_CITRUS_VERSION,
                CamelKitMain.distribution(),
                printer(),
                initProgress(),
                initReporter(),
                dryRun,
                replaceWrapper,
                recordBaseline,
                replace);
    }

    private void printConflicts(InitResult result) {
        if (result.conflicts().isEmpty()) {
            return;
        }
        printer().println("  " + yellow("\u26A0") + bold("  Existing files preserved"));
        printer().println(dim("  Not written by Camel Kit, or changed since, so left as they are:"));
        for (ManagedFiles.Change conflict : result.conflicts()) {
            String proposal = conflict.proposal() == null
                    ? ""
                    : dim("  \u2190 Camel Kit version: ")
                      + result.targetDir().relativize(conflict.proposal()).toString().replace('\\', '/');
            printer().println("     " + cyan(conflict.path()) + proposal);
        }
        printer().println(dim("  Merge the Camel Kit version by hand, or replace a file with ")
                          + bold("--force --replace <path>"));
        printer().println();
    }

    void printNextSteps(String projectName, String agentName, String agentId) {
        String divider = "  " + "\u2500".repeat(34);
        printer().println("  " + bold("Next steps"));
        printer().println(divider);
        printer().println("  1  Open " + cyan(projectName) + " in " + agentName);
        if (AgentGeneratorStrategy.BOB2.descriptorValue().equalsIgnoreCase(agentId)) {
            printer().println("  2  Bob Shell: open " + cyan("/skills") + ", then invoke " + cyan("$camel-start"));
            printer().println("     Migration shortcut: " + cyan("$camel-migrate"));
            printer().println("  3  Bob IDE: use " + cyan("/camel-start") + " or " + cyan("/camel-migrate"));
            printer().println();
            return;
        }
        if (AgentGeneratorStrategy.CODEX.descriptorValue().equalsIgnoreCase(agentId)) {
            printer().println("  2  Start Codex from the project root and review the repository before trusting it");
            printer().println("  3  Run " + cyan("/skills") + ", then invoke " + cyan("$camel-start"));
            printer().println("     Use " + cyan("/mcp") + " to verify Camel Kit MCP servers");
            printer().println();
            return;
        }
        if (AgentGeneratorStrategy.ANTIGRAVITY.descriptorValue().equalsIgnoreCase(agentId)) {
            printer().println("  2  Use " + cyan("/camel-start") + " or ask for the " + cyan("camel-start") + " skill");
            printer().println("     In Antigravity CLI, inspect native skills with " + cyan("/skills"));
            printer().println(
                    "  3  Verify the project MCP servers through Antigravity's MCP settings or " + cyan("/mcp"));
            printer().println();
            return;
        }
        if (AgentGeneratorStrategy.COPILOT.descriptorValue().equalsIgnoreCase(agentId)) {
            printer().println("  2  Ask Copilot: " + cyan("\"Use the /camel-start skill.\""));
            printer().println("     Run " + cyan("/skills list") + " to inspect available project skills");
            printer().println("  3  Use " + cyan("/mcp show") + " to verify Camel Kit MCP servers");
            printer().println();
            return;
        }
        if (AgentGeneratorStrategy.PI.descriptorValue().equalsIgnoreCase(agentId)) {
            String adapter = "pi install npm:pi-mcp-adapter@" + CamelKitMain.distribution().piMcpAdapterVersion();
            printer().println("  2  Install MCP adapter: " + cyan(adapter));
            printer().println("  3  In Pi, run " + cyan("/trust") + " then " + cyan("/skill:camel-start"));
            printer().println("     Use " + cyan("/mcp status") + " to verify Camel Kit MCP servers");
            printer().println();
            return;
        }
        printer().println("  2  " + cyan("/camel-start") + "  \u2014 route integration work");
        printer().println("     " + cyan("/camel-migrate") + " \u2014 migration shortcut");
        printer().println("  3  " + cyan("/camel-debug") + "  \u2014 troubleshoot broken routes");
        printer().println("     " + cyan("/camel-knowledge") + " \u2014 ask Camel documentation questions");
        printer().println();
    }

    private InitProgress initProgress() {
        return new InitProgress() {
            @Override
            public void startTask(String icon, String label) {
                main.getTaskTracker().startTask(icon, label);
            }

            @Override
            public void finishTask() {
                main.getTaskTracker().finishTask();
            }
        };
    }

    private InitReporter initReporter() {
        return new InitReporter() {
            @Override
            public void mavenWrapperCreated(String mavenVersion) {
                printer().println(green("\u2713") + " Maven Wrapper created (portable Maven " + mavenVersion + ")");
            }

            @Override
            public void mavenWrapperPreserved() {
                printer().println(green("\u2713") + " Existing Maven Wrapper kept");
            }

            @Override
            public void graphBuilt(InitGraphSummary graph) {
                printer().println(green("\u2713") + " Project graph: " + graph.nodeCount()
                                  + " nodes, " + graph.edgeCount() + " edges");
                if (graph.detectedProjectType() != null && !graph.detectedProjectType().isEmpty()) {
                    printer().println("     Detected: " + cyan(graph.detectedProjectType()));
                }
            }

            @Override
            public void graphSkipped() {
                printer().println(yellow("  No source files found \u2014 graph skipped"));
            }

            @Override
            public void warning(InitWarning warning) {
                printer().println(yellow("  Warning: " + warning.message()));
            }
        };
    }

    private void printSummary(String projectName, String agentName, int citrusSchemaCount) {
        printer().println();
        printer().print("  " + green("\u2713") + "  ");
        printer().println(bold(projectName));
        String meta = "     " + agentName
                      + (citrusSchemaCount > 0 ? "  \u00b7  " + citrusSchemaCount + " schemas" : "");
        printer().println(meta);
        printer().println();
    }

    private String detectCommandPrefix() {
        String cmdLine = ProcessHandle.current().info().commandLine().orElse("");
        if (cmdLine.contains("camel-kit"))
            return "camel-kit";
        return "camel kit";
    }
}
