package org.apache.camel.kit.command.ship;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.camel.kit.command.HelpAwareCommand;
import org.apache.camel.kit.command.InformationOptionParser;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.ship.context.ShipContext;
import org.apache.camel.kit.ship.controller.ShipController;
import org.apache.camel.kit.ship.controller.ShipNativeWorker;
import org.apache.camel.kit.ship.controller.ShipRun;
import org.apache.camel.kit.ship.controller.ShipRun.Oversight;
import org.apache.camel.kit.ship.controller.ShipRun.Stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aesh.command.CommandDefinition;
import org.aesh.command.converter.Converter;
import org.aesh.command.converter.ConverterInvocation;
import org.aesh.command.impl.internal.ProcessedOption;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import org.aesh.command.option.OptionVisibility;
import org.aesh.command.parser.OptionParserException;
import org.aesh.command.validator.OptionValidatorException;
import org.aesh.parser.ParsedLineIterator;

/**
 * Local Ship command. Starting or resuming a run drives the authoritative coordinator workflow; status and abort remain
 * pure controller operations.
 */
@CommandDefinition(
                   name = "ship",
                   generateHelp = true,
                   description = "Technology Preview: start, inspect, resume, or abort a local Camel Ship run",
                   footer = "Camel Ship is still being stabilized, may change, and is not recommended for production use.")
public final class ShipCommand extends HelpAwareCommand {
    private ShipController controller;
    private WorkflowLauncher launcher;

    Operation operation;

    @Option(name = "version", shortName = 'V', hasValue = false, overrideRequired = true,
            parser = InformationOptionParser.class,
            description = "Print version information and exit")
    boolean versionRequested;

    @Option(parser = KitOptionParser.class, name = "submit", argument = "RUN_ID",
            description = "Submit a native subagent result")
    String submit;
    @Option(parser = KitOptionParser.class, name = "resume", argument = "RUN_ID",
            description = "Resume an existing run")
    String resume;
    @Option(parser = KitOptionParser.class, name = "start-from", argument = "STAGE",
            converter = StageConverter.class,
            description = "Start at discovery, design, or plan")
    Stage startFrom;
    @Option(parser = KitOptionParser.class, name = "status", argument = "RUN_ID",
            description = "Show an existing run")
    String status;
    @Option(parser = KitOptionParser.class, name = "abort", argument = "RUN_ID",
            description = "Abort an existing run")
    String abort;

    @OptionList(name = "text", description = "Add text context", valueSeparator = OptionList.NO_SEPARATOR,
                parser = ContextParser.class, converter = ContextConverter.class)
    List<ContextArgument> texts = new ArrayList<>();
    @OptionList(name = "document", description = "Add document context", valueSeparator = OptionList.NO_SEPARATOR,
                parser = ContextParser.class, converter = ContextConverter.class)
    List<ContextArgument> documents = new ArrayList<>();

    @Option(parser = KitOptionParser.class,
            name = "ask",
            argument = "POLICY",
            converter = OversightConverter.class,
            description = "Oversight policy: always, smart, or never")
    Oversight oversight;

    @Option(parser = KitOptionParser.class, name = "project-dir", defaultValue = ".",
            visibility = OptionVisibility.HIDDEN,
            converter = PathConverter.class)
    Path projectDirectory;

    @Option(parser = KitOptionParser.class, name = "backend",
            converter = ExecutionModeConverter.class,
            description = "Execution backend for a new run: pi (default), bob2-native, copilot-native,"
                          + " claude-native, antigravity-native, qwen-native, codex-native or opencode-native")
    ShipRun.ExecutionMode executionMode;

    @Option(parser = KitOptionParser.class, name = "json",
            description = "Return structured run state and any pending native task", hasValue = false)
    boolean json;

    @Option(parser = KitOptionParser.class, name = "result", argument = "PATH",
            description = "Native result envelope for --submit",
            converter = PathConverter.class)
    Path resultFile;

    @Option(parser = KitOptionParser.class,
            name = "pi",
            argument = "PATH",
            description = "Pi executable (default: discovered on PATH)",
            converter = PathConverter.class)
    Path piExecutable;

    @Option(parser = KitOptionParser.class,
            name = "node",
            argument = "PATH",
            description = "Node executable (default: discovered on PATH)",
            converter = PathConverter.class)
    Path nodeExecutable;

    @Option(parser = KitOptionParser.class,
            name = "maven-repository",
            argument = "PATH",
            description = "Private Maven repository for validation catalogs"
                          + " (default: under the ship state directory)",
            converter = PathConverter.class)
    Path mavenRepository;

    @Option(parser = KitOptionParser.class,
            name = "stage-timeout",
            argument = "DURATION",
            converter = StageTimeoutConverter.class,
            description = "Time limit for one stage attempt, like 90s, 10m, or 1h (default: 10m)")
    Duration stageTimeout;

    @Option(parser = KitOptionParser.class,
            name = "accept-experimental",
            description = "Accept an experimental Pi or Node version after its warning", hasValue = false)
    Boolean acceptExperimental;

    @Option(parser = KitOptionParser.class,
            name = "config", shortName = 'c',
            argument = "PATH",
            description = "Config properties file (default: ~/.camel-kit/config.properties)",
            converter = PathConverter.class)
    Path configFile;

    @OptionList(parser = KitOptionParser.class,
                name = "property", shortName = 'p',
                description = "Override a config property (repeatable)", valueSeparator = OptionList.NO_SEPARATOR)
    List<String> configProperties = new ArrayList<>();

    public ShipCommand() {
        this(null, null);
    }

    ShipCommand(ShipController controller, WorkflowLauncher launcher) {
        this.controller = controller;
        this.launcher = launcher;
    }

    @Override
    public Integer call() {
        if (versionRequested) {
            return 0;
        }
        validateArguments();
        try {
            ShipRun run = execute();
            if (json) {
                printJson(run);
            } else {
                printSummary(run);
            }
            return workflowOperation() && run.status() == ShipRun.RunStatus.FAILED ? 1 : 0;
        } catch (ShipController.Failure e) {
            return failure(e.code(), e.getMessage());
        } catch (CommandFailure e) {
            return failure(e.code, e.getMessage());
        }
    }

    private ShipRun execute() {
        if (operation != null && operation.status != null) {
            return controller().status(operation.status);
        }
        if (operation != null && operation.abort != null) {
            return controller().abort(operation.abort);
        }
        Workflow workflow = launchWorkflow();
        if (operation != null && operation.submit != null) {
            return awaitWorkflow(runId -> workflow.submit(runId, resultFile), operation.submit);
        }
        if (operation != null && operation.resume != null) {
            List<ShipContext.Input> additions = inputs();
            return awaitWorkflow(
                    runId -> workflow.resume(runId, additions), operation.resume);
        }
        ShipRun run = operation == null
                ? controller().start(projectDirectory, selectedOversight(), inputs(), selectedExecutionMode())
                : controller().startFrom(projectDirectory, operation.startFrom, selectedOversight(), inputs(),
                        selectedExecutionMode());
        return awaitWorkflow(workflow::run, run.id());
    }

    private boolean workflowOperation() {
        return operation == null || operation.resume != null || operation.startFrom != null || operation.submit != null;
    }

    private Workflow launchWorkflow() {
        try {
            return launcher().launch(new RuntimeSettings(
                    piExecutable,
                    nodeExecutable,
                    mavenRepository,
                    stageTimeout,
                    Boolean.TRUE.equals(acceptExperimental),
                    configFile,
                    configProperties,
                    selectedExecutionMode()));
        } catch (IllegalArgumentException e) {
            throw new CommandFailure("runtime-unavailable", e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new CommandFailure("runtime-unsupported", e.getMessage(), e);
        }
    }

    private ShipRun awaitWorkflow(WorkflowStep step, String runId) {
        try {
            return step.apply(runId);
        } catch (ShipController.Failure e) {
            // Keep the run reachable: without a list command the error line is the only place
            // the identifier can surface after a post-start failure.
            throw new CommandFailure(e.code(), e.getMessage() + runReference(runId), e);
        } catch (ClosedByInterruptException e) {
            // The abort watcher's interrupt can land inside an interruptible channel operation
            // and surface as this message-less IOException instead of InterruptedException.
            return statusAfterInterrupt(runId);
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new CommandFailure("workflow-failed", message + runReference(runId), e);
        } catch (InterruptedException e) {
            return statusAfterInterrupt(runId);
        }
    }

    /**
     * Reports the latest durable state like --abort does instead of surfacing a stack trace. Reaching this method
     * proves an interruption occurred, so the flag is re-asserted unconditionally on exit — restoring it only when
     * still set would lose the interruption, because a caught InterruptedException usually arrives with the flag
     * already cleared. The initial clear exists for the ClosedByInterruptException caller, which arrives with the flag
     * still set and would otherwise fail the interruptible status read.
     */
    private ShipRun statusAfterInterrupt(String runId) {
        Thread.interrupted();
        try {
            return controller().status(runId);
        } finally {
            Thread.currentThread().interrupt();
        }
    }

    private static String runReference(String runId) {
        return " (run " + runId + ")";
    }

    private ShipController controller() {
        if (controller == null) {
            try {
                controller = new ShipController(
                        ShipController.defaultStateRoot(projectDirectory, System.getenv()));
            } catch (IllegalArgumentException e) {
                throw new CommandFailure("runtime-unavailable", e.getMessage(), e);
            } catch (IllegalStateException e) {
                throw new CommandFailure("runtime-unsupported", e.getMessage(), e);
            }
        }
        return controller;
    }

    private WorkflowLauncher launcher() {
        if (launcher == null) {
            launcher = new ShipRuntime(
                    ShipController.defaultStateRoot(projectDirectory, System.getenv()));
        }
        return launcher;
    }

    private void validateArguments() {
        long operations
                = Stream.of(submit, resume, startFrom, status, abort).filter(Objects::nonNull).count();
        if (operations > 1) {
            throw new UsageException("--submit, --resume, --start-from, --status and --abort are mutually exclusive");
        }
        operation = operations == 0 ? null : new Operation(submit, resume, startFrom, status, abort);
        boolean submission = operation != null && operation.submit != null;
        if (submission != (resultFile != null)) {
            throw new UsageException("--submit and --result must be supplied together");
        }
        if (submission && (!texts.isEmpty() || !documents.isEmpty())) {
            throw new UsageException("--submit does not accept context additions");
        }
        if (operation != null && (operation.status != null || operation.abort != null) && executionMode != null) {
            throw new UsageException("--backend is only valid for workflow operations");
        }
        if (executionMode != null && executionMode.isNative()
                && (piExecutable != null || nodeExecutable != null || acceptExperimental != null)) {
            throw new UsageException("Pi/Node options cannot configure a native backend");
        }

        if (operation != null && operation.startFrom == null && oversight != null) {
            throw new UsageException("--ask is only valid when starting a run");
        }
        if (operation != null && (operation.status != null || operation.abort != null)
                && (!texts.isEmpty() || !documents.isEmpty())) {
            throw new UsageException("--text and --document are only valid when starting or resuming a run");
        }
        if (operation != null && (operation.status != null || operation.abort != null)
                && (piExecutable != null || nodeExecutable != null || mavenRepository != null
                        || stageTimeout != null || acceptExperimental != null || configFile != null
                        || !configProperties.isEmpty())) {
            throw new UsageException("Runtime and config options are only valid when starting or resuming a run");
        }
    }

    private ShipRun.ExecutionMode selectedExecutionMode() {
        String runId = operation == null ? null
                : operation.resume != null ? operation.resume : operation.submit;
        if (runId != null) {
            ShipRun.ExecutionMode recorded = controller().status(runId).executionMode();
            if (executionMode != null && executionMode != recorded) {
                throw new CommandFailure(
                        "execution-mode-mismatch", "Ship execution mode is fixed for this run: "
                                                   + recorded,
                        null);
            }
            return recorded;
        }
        return executionMode == null ? ShipRun.ExecutionMode.PI : executionMode;
    }

    private Oversight selectedOversight() {
        return oversight == null ? Oversight.SMART : oversight;
    }

    /** Bound context in the original command-line order. */
    public List<ShipContext.Input> inputs() {
        return Stream.concat(texts.stream(), documents.stream())
                .sorted(Comparator.comparingInt(ContextArgument::position)).map(ContextArgument::input).toList();
    }

    private void printJson(ShipRun run) {
        try {
            ShipNativeWorker.Task task = controller().pendingTask(run);
            this.out.println(new ObjectMapper().writeValueAsString(new JsonResponse(1, run, task)));
            this.out.flush();
            if (run.status() == ShipRun.RunStatus.COMPLETED) {
                this.err.println(DOCUMENTATION_FOLLOW_UP);
                this.err.flush();
            }
        } catch (IOException e) {
            throw new CommandFailure("handoff-read-failed", "Could not read native task" + runReference(run.id()), e);
        }
    }

    private record JsonResponse(int schemaVersion, ShipRun run, ShipNativeWorker.Task task) {
    }

    private static final String DOCUMENTATION_FOLLOW_UP
            = "Documentation: requires a separate review of the published project; "
              + "use /camel-start reconcile documentation. Ship completion does not certify current docs or ADRs.";

    private void printSummary(ShipRun run) {
        PrintWriter writer = this.out;
        writer.println("Run: " + run.id());
        writer.println("Feature status: Technology Preview — still being stabilized; "
                       + "may change; not recommended for production use.");
        writer.println("Status: " + run.status());
        writer.println("Stage: " + run.currentStage());
        writer.println("Oversight: " + run.oversight());
        if (run.executionMode() != ShipRun.ExecutionMode.PI) {
            writer.println("Backend: " + run.executionMode());
        }
        if (run.status() == ShipRun.RunStatus.PAUSED) {
            writer.println("Paused after: " + pausedAfter(run));
            if (run.message() != null) {
                writer.println("Report:");
                for (String line : safeDisplay(run.message(), true).split("\n", -1)) {
                    writer.println("  " + line);
                }
            }
        } else if (run.message() != null) {
            writer.println("Message: " + safeDisplay(run.message(), false));
        }
        for (ShipRun.StageRecord stage : run.stages()) {
            if (!stage.unansweredQuestions().isEmpty()) {
                writer.println("Unanswered questions (" + stage.stage() + "):");
                for (ShipRun.UnansweredQuestion question : stage.unansweredQuestions()) {
                    writer.println("  Question: " + safeDisplay(question.question(), false));
                    writer.println("  Default applied: " + (question.defaultApplied() == null
                            ? "none"
                            : safeDisplay(question.defaultApplied(), false)));
                }
            } else if (stage.materialAmbiguity()) {
                writer.println("Warning (" + stage.stage() + "): Material ambiguity reported; "
                               + "structured questions/defaults unavailable for this legacy result.");
            }
        }
        List<ShipRun.ArtifactRef> validation = run.stage(Stage.VALIDATE).artifacts();
        if (!validation.isEmpty()) {
            writer.println("Stamp: " + safeDisplay(validation.get(0).path(), false));
        }
        if (run.publication() != null) {
            writer.println("Publication: " + safeDisplay(run.publication().path(), false));
        }
        if (run.status() == ShipRun.RunStatus.COMPLETED) {
            writer.println(DOCUMENTATION_FOLLOW_UP);
        }
        if ((run.status() == ShipRun.RunStatus.PAUSED
                || run.status() == ShipRun.RunStatus.RUNNING
                || run.status() == ShipRun.RunStatus.FAILED)
                && (configFile != null || !configProperties.isEmpty())) {
            writer.println("Config: Repeat the same -c/-p options when resuming this run.");
        }
        if (run.status() == ShipRun.RunStatus.PAUSED) {
            if (pausedAfter(run) == Stage.VALIDATE) {
                writer.println("Warning: Adding context restarts from DISCOVERY and discards the validation Stamp.");
            }
            writer.println("Next: " + commandName() + " --resume " + run.id()
                           + projectDirectoryOption(run) + " [--text TEXT | --document PATH]");
        } else if (run.status() == ShipRun.RunStatus.RUNNING
                || run.status() == ShipRun.RunStatus.FAILED) {
            writer.println("Next: " + commandName() + " --resume " + run.id()
                           + projectDirectoryOption(run));
        }
        writer.flush();
    }

    /** The run store is project-local, so a continuation issued from elsewhere must name the run's project. */
    private static String projectDirectoryOption(ShipRun run) {
        Path project = Path.of(run.projectDirectory());
        try {
            if (project.equals(Path.of("").toAbsolutePath().toRealPath())) {
                return "";
            }
        } catch (IOException | RuntimeException e) {
            // The working directory cannot be resolved; name the project explicitly.
        }
        return " --project-dir '" + project.toString().replace("'", "'\\''") + "'";
    }

    private static Stage pausedAfter(ShipRun run) {
        Stage completed = run.currentStage();
        for (ShipRun.StageRecord stage : run.stages()) {
            if (stage.status() == ShipRun.StageStatus.COMPLETED) {
                completed = stage.stage();
            }
        }
        return completed;
    }

    private static String safeDisplay(String value, boolean multiline) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (codePoint == '\n' && multiline) {
                safe.append('\n');
            } else if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE) {
                safe.append(' ');
            } else {
                safe.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        String display = safe.toString().strip();
        return display.isEmpty() ? "[unprintable]" : display;
    }

    private Integer failure(String code, String message) {
        PrintWriter writer = this.err;
        writer.println("Error [" + code + "]: "
                       + (message == null ? "[unprintable]" : safeDisplay(message, false)));
        writer.flush();
        return 1;
    }

    /** Runs the authoritative coordinator workflow over an existing run. */
    interface Workflow {

        ShipRun run(String runId) throws IOException, InterruptedException;

        ShipRun resume(String runId, List<? extends ShipContext.Input> additions)
                throws IOException, InterruptedException;

        default ShipRun submit(String runId, Path resultFile) throws IOException, InterruptedException {
            throw new IllegalArgumentException("Result submission requires a native backend");
        }

    }

    /** Builds the runtime-backed workflow, failing fast before any run state exists. */
    @FunctionalInterface
    interface WorkflowLauncher {

        Workflow launch(RuntimeSettings settings);
    }

    /** Raw runtime option values; the launcher resolves defaults and discovery. */
    record RuntimeSettings(Path piExecutable, Path nodeExecutable, Path mavenRepository,
            Duration stageTimeout, boolean acceptExperimental, Path configFile,
            List<String> configProperties, ShipRun.ExecutionMode executionMode) {
        RuntimeSettings(Path piExecutable, Path nodeExecutable, Path mavenRepository,
                        Duration stageTimeout, boolean acceptExperimental, Path configFile,
                        List<String> configProperties) {
            this(piExecutable, nodeExecutable, mavenRepository, stageTimeout, acceptExperimental, configFile,
                 configProperties, ShipRun.ExecutionMode.PI);
        }
    }

    @FunctionalInterface
    private interface WorkflowStep {

        ShipRun apply(String runId) throws IOException, InterruptedException;
    }

    private static final class CommandFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        CommandFailure(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    private record Operation(String submit, String resume, Stage startFrom, String status, String abort) {
    }

    record ContextArgument(int position, ShipContext.Input input) {
    }

    public static final class ContextParser extends KitOptionParser {
        @Override
        public void parse(ParsedLineIterator iterator, ProcessedOption option) throws OptionParserException {
            int position = iterator.peekParsedWord().lineIndex();
            int previous = option.getValues().size();
            super.parse(iterator, option);
            // Aesh binds each option separately; carry the argv position through its string-only converter API.
            for (int i = previous; i < option.getValues().size(); i++) {
                option.getValues().set(i, position + ":" + option.name() + ":" + option.getValues().get(i));
            }
        }
    }

    public static final class ContextConverter implements Converter<ContextArgument, ConverterInvocation> {
        @Override
        public ContextArgument convert(ConverterInvocation invocation) throws OptionValidatorException {
            String[] value = invocation.getInput().split(":", 3);
            try {
                ShipContext.Input input = value[1].equals("text")
                        ? new ShipContext.TextInput(value[2]) : new ShipContext.DocumentInput(Path.of(value[2]));
                return new ContextArgument(Integer.parseInt(value[0]), input);
            } catch (IllegalArgumentException e) {
                throw new OptionValidatorException(e.getMessage());
            }
        }
    }

    public static final class ExecutionModeConverter implements Converter<ShipRun.ExecutionMode, ConverterInvocation> {
        @Override
        public ShipRun.ExecutionMode convert(ConverterInvocation invocation) throws OptionValidatorException {
            String value = invocation.getInput();
            return switch (value) {
                case "pi" -> ShipRun.ExecutionMode.PI;
                case "bob2-native" -> ShipRun.ExecutionMode.BOB2_NATIVE;
                case "copilot-native" -> ShipRun.ExecutionMode.COPILOT_NATIVE;
                case "claude-native" -> ShipRun.ExecutionMode.CLAUDE_NATIVE;
                case "codex-native" -> ShipRun.ExecutionMode.CODEX_NATIVE;
                case "antigravity-native" -> ShipRun.ExecutionMode.ANTIGRAVITY_NATIVE;
                case "qwen-native" -> ShipRun.ExecutionMode.QWEN_NATIVE;
                case "opencode-native" -> ShipRun.ExecutionMode.OPENCODE_NATIVE;
                default -> throw new OptionValidatorException(
                        "expected pi, bob2-native, copilot-native, claude-native, antigravity-native, qwen-native, codex-native or opencode-native");
            };
        }
    }

    public static final class OversightConverter implements Converter<Oversight, ConverterInvocation> {

        @Override
        public Oversight convert(ConverterInvocation invocation) throws OptionValidatorException {
            String value = invocation.getInput();
            try {
                return Oversight.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new OptionValidatorException("expected always, smart, or never");
            }
        }
    }

    public static final class StageConverter implements Converter<Stage, ConverterInvocation> {

        @Override
        public Stage convert(ConverterInvocation invocation) throws OptionValidatorException {
            String value = invocation.getInput();
            try {
                return Stage.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new OptionValidatorException(
                        "expected discovery, design, or plan");
            }
        }
    }

    public static final class StageTimeoutConverter implements Converter<Duration, ConverterInvocation> {

        private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)([smh])");

        @Override
        public Duration convert(ConverterInvocation invocation) throws OptionValidatorException {
            String value = invocation.getInput();
            Matcher matcher = DURATION.matcher(value);
            if (!matcher.matches()) {
                throw new OptionValidatorException("expected a duration like 90s, 10m, or 1h");
            }
            try {
                long amount = Long.parseLong(matcher.group(1));
                return switch (matcher.group(2)) {
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    default -> Duration.ofHours(amount);
                };
            } catch (NumberFormatException | ArithmeticException e) {
                throw new OptionValidatorException("expected a duration like 90s, 10m, or 1h");
            }
        }
    }
}
