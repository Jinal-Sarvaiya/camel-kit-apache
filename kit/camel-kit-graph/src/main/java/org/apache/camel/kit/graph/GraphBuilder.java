package org.apache.camel.kit.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.camel.kit.graph.model.GraphEdge;
import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.parser.*;

public class GraphBuilder {

    private static final long DEFAULT_PARSER_TIMEOUT = 60;
    private static final TimeUnit DEFAULT_PARSER_TIMEOUT_UNIT = TimeUnit.SECONDS;

    private final GraphParser pomParser;
    private final List<GraphParser> remainingParsers;
    private final Map<GraphParser, MigrationProvider> migrationProviders = new IdentityHashMap<>();
    private final long parserTimeout;
    private final TimeUnit parserTimeoutUnit;

    public GraphBuilder() {
        this(Map.of("mule-xml", new MuleXmlFlowParser()));
    }

    /** Use exactly the selected migration providers, in catalog ID order at the original Mule merge position. */
    public GraphBuilder(Map<String, ? extends MigrationParser> migrationParsers) {
        pomParser = new PomParser();
        remainingParsers = contentParsers(migrationParsers);
        parserTimeout = DEFAULT_PARSER_TIMEOUT;
        parserTimeoutUnit = DEFAULT_PARSER_TIMEOUT_UNIT;
    }

    private List<GraphParser> contentParsers(Map<String, ? extends MigrationParser> migrationParsers) {
        List<GraphParser> parsers = new ArrayList<>(
                List.of(new JavaGraphParser(), new GroovyGraphParser(),
                        new XmlRouteParser()));
        new TreeMap<>(migrationParsers).forEach((id, parser) -> {
            registerMigrationParser("provider/" + id, parser);
            parsers.add(parser);
        });
        parsers.addAll(List.of(new DataWeaveParser(), new BizTalkParser(), new YamlRouteParser(), new ConfigParser()));
        return parsers;
    }

    GraphBuilder(
                 GraphParser pomParser, List<GraphParser> remainingParsers,
                 long parserTimeout, TimeUnit parserTimeoutUnit) {
        this.pomParser = Objects.requireNonNull(pomParser, "pomParser");
        this.remainingParsers = List.copyOf(Objects.requireNonNull(remainingParsers, "remainingParsers"));
        for (var parser : this.remainingParsers) {
            if (parser instanceof MigrationParser migration) {
                registerMigrationParser(parserName(parser), migration);
            }
        }
        if (parserTimeout <= 0) {
            throw new IllegalArgumentException("parserTimeout must be positive");
        }
        this.parserTimeout = parserTimeout;
        this.parserTimeoutUnit = Objects.requireNonNull(parserTimeoutUnit, "parserTimeoutUnit");
    }

    private void registerMigrationParser(String name, MigrationParser parser) {
        Set<String> declared;
        try {
            int version = parser.apiVersion();
            if (version != MigrationParser.API_VERSION) {
                throw new IllegalArgumentException(
                        "requires API " + version + ", supported: " + MigrationParser.API_VERSION);
            }
            declared = parser.inputExtensions();
            if (declared == null || declared.isEmpty() || declared.stream().anyMatch(ext -> ext == null || ext.isBlank()
                    || !ext.matches("[^./\\\\]+") || !ext.equals(ext.toLowerCase(Locale.ROOT)))) {
                throw new IllegalArgumentException("invalid input extensions: " + declared);
            }
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalArgumentException("Parser " + name + " " + e.getMessage(), e);
        }
        var previous = migrationProviders.put(parser, new MigrationProvider(name, Set.copyOf(declared)));
        if (previous != null) {
            throw new IllegalArgumentException(name + " and " + previous.name() + " need separate parser instances");
        }
    }

    public ProjectGraph build(Path projectRoot) {
        GraphBuildResult result = buildWithDiagnostics(projectRoot);
        if (!result.successful()) {
            throw new IllegalStateException("Graph build failed: " + result.failureSummary());
        }
        return result.graph();
    }

    public GraphBuildResult buildWithDiagnostics(Path projectRoot) {
        ProjectGraph graph = new ProjectGraph();
        List<ParserDiagnostic> diagnostics = new ArrayList<>();
        List<Path> projectFiles;
        try {
            Set<String> extensions = new HashSet<>();
            migrationProviders.values().forEach(provider -> extensions.addAll(provider.extensions()));
            projectFiles = GraphParser.projectFiles(projectRoot, extensions);
        } catch (RuntimeException e) {
            diagnostics.add(failureDiagnostic(
                    "ProjectFileScanner",
                    List.of(),
                    e,
                    0));
            return new GraphBuildResult(graph, diagnostics);
        }
        pomParser.resetWarnings();
        remainingParsers.forEach(GraphParser::resetWarnings);

        // Run PomParser first so MAVEN_ARTIFACT nodes are available for all other parsers
        ParserRun pomRun
                = runFirstParser(projectRoot, projectFiles);
        diagnostics.add(pomRun.diagnostic());
        if (pomRun.diagnostic().successful()) {
            mergeDeterministically(graph, pomRun.graph());
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(remainingParsers.size(), Runtime.getRuntime().availableProcessors())),
                GraphBuilder::parserThread);
        try {
            ProjectGraph baseGraph = new ProjectGraph();
            baseGraph.merge(graph);

            List<ParserTask> tasks = remainingParsers.stream()
                    .map(parser -> parserTask(parser, projectRoot, projectFiles))
                    .toList();

            List<ParserFuture> futures = tasks.stream()
                    .map(task -> {
                        long submittedAt = System.nanoTime();
                        return new ParserFuture(
                                task,
                                executor.submit(
                                        () -> runParser(task.parser(), projectRoot, baseGraph, task)),
                                submittedAt);
                    })
                    .toList();

            Map<String, String> migrationNodeOwners = new HashMap<>();
            for (int i = 0; i < futures.size(); i++) {
                ParserFuture parserFuture = futures.get(i);
                try {
                    long remaining = parserTimeoutUnit.toNanos(parserTimeout)
                                     - (System.nanoTime() - parserFuture.submittedAt());
                    ParserRun parserRun = parserFuture.future().get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                    ParserDiagnostic diagnostic = parserRun.diagnostic();
                    if (diagnostic.successful()) {
                        var provider = migrationProviders.get(parserFuture.task().parser());
                        if (provider != null && migrationProviders.size() > 1) {
                            diagnostic = warnOnNodeConflicts(provider.name(), migrationNodeOwners, parserRun, graph);
                        }
                        mergeDeterministically(graph, parserRun.graph());
                    }
                    diagnostics.add(diagnostic);
                } catch (ExecutionException e) {
                    ParserDiagnostic diagnostic = failureDiagnostic(
                            parserFuture.task().parser(),
                            parserFuture.task().scannedFiles(),
                            e.getCause(),
                            elapsedMillis(parserFuture.submittedAt()));
                    diagnostics.add(diagnostic);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    parserFuture.future().cancel(true);
                    diagnostics.add(
                            interruptedDiagnostic(parserFuture.task().parser(), parserFuture.task().scannedFiles()));
                    addSkippedAfterInterruptionDiagnostics(futures, i + 1, diagnostics);
                    break;
                } catch (TimeoutException e) {
                    parserFuture.future().cancel(true);
                    diagnostics
                            .add(timeoutDiagnostic(parserFuture.task().parser(), parserFuture.task().scannedFiles()));
                }
            }
        } finally {
            executor.shutdownNow();
        }

        CrossLinker crossLinker = new CrossLinker();
        crossLinker.link(graph);

        PropertyBindingParser propertyBindingParser = new PropertyBindingParser();
        propertyBindingParser.parse(graph, RuntimeDetector.detect(graph));
        propertyBindingParser.resolvePlaceholders(graph);

        crossLinker.expandInterfaces(graph);

        return new GraphBuildResult(graph, diagnostics);
    }

    public void buildAndSerialize(Path projectRoot, Path outputFile) {
        ProjectGraph graph = build(projectRoot);
        try {
            Files.createDirectories(outputFile.getParent());
            GraphSerializer.write(graph, outputFile, projectRoot.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize graph to " + outputFile, e);
        }
    }

    private ParserRun runParser(
            GraphParser parser, Path projectRoot, ProjectGraph baseGraph,
            ParserTask task) {
        long startedAt = System.nanoTime();
        ProjectGraph delta = new ProjectGraph();
        if (task.preparationFailure() != null) {
            ParserDiagnostic diagnostic = failureDiagnostic(
                    parser,
                    task.scannedFiles(),
                    task.preparationFailure(),
                    elapsedMillis(startedAt));
            return new ParserRun(delta, diagnostic);
        }
        List<String> scannedFiles = task.scannedFiles();
        try {
            ProjectGraph fragment;
            if (parser instanceof MigrationParser migration) {
                List<Path> candidates = task.scannedFilePaths();
                GraphParser.requireSafeFiles(projectRoot, candidates);
                List<Path> inputs = candidates.stream().filter(migration::acceptsContent).toList();
                scannedFiles = GraphParser.relativeFileNames(projectRoot, inputs);
                // Publish the parse inputs before the worker can time out.
                task.selectedFiles().set(scannedFiles);
                fragment = new ProjectGraph();
                fragment.merge(baseGraph);
                if (!inputs.isEmpty()) {
                    migration.parseFiles(projectRoot, inputs, fragment);
                }
            } else {
                fragment = parser.parseFragment(projectRoot, baseGraph, task.scannedFilePaths());
            }
            delta = deltaFrom(fragment, baseGraph);
            if (System.nanoTime() - startedAt > parserTimeoutUnit.toNanos(parserTimeout)) {
                return new ParserRun(new ProjectGraph(), timeoutDiagnostic(parser, scannedFiles));
            }
            ParserDiagnostic diagnostic = diagnostic(
                    parser,
                    scannedFiles,
                    List.of(),
                    collectWarnings(parser),
                    List.of(),
                    false,
                    delta.nodeCount(),
                    delta.edgeCount(),
                    elapsedMillis(startedAt));
            return new ParserRun(delta, diagnostic);
        } catch (RuntimeException e) {
            ParserDiagnostic diagnostic = failureDiagnostic(parser, scannedFiles, e, elapsedMillis(startedAt));
            return new ParserRun(delta, diagnostic);
        }
    }

    private ParserRun runFirstParser(Path projectRoot, List<Path> projectFiles) {
        ParserTask task = parserTask(pomParser, projectRoot, projectFiles);
        ExecutorService executor = Executors.newSingleThreadExecutor(GraphBuilder::parserThread);
        Future<ParserRun> future = executor.submit(() -> runParser(pomParser, projectRoot, new ProjectGraph(), task));
        long startedAt = System.nanoTime();
        try {
            return future.get(parserTimeout, parserTimeoutUnit);
        } catch (TimeoutException e) {
            return new ParserRun(new ProjectGraph(), timeoutDiagnostic(pomParser, task.scannedFiles()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ParserRun(new ProjectGraph(), interruptedDiagnostic(pomParser, task.scannedFiles()));
        } catch (ExecutionException e) {
            return new ParserRun(
                    new ProjectGraph(), failureDiagnostic(pomParser, task.scannedFiles(),
                            e.getCause(), elapsedMillis(startedAt)));
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    private static Thread parserThread(Runnable task) {
        Thread thread = new Thread(task, "camel-kit-graph-parser");
        // A timed-out library parser must not keep the CLI alive if it ignores interruption.
        thread.setDaemon(true);
        return thread;
    }

    private ParserTask parserTask(GraphParser parser, Path projectRoot, List<Path> projectFiles) {
        try {
            List<Path> scannedFilePaths;
            var provider = migrationProviders.get(parser);
            if (provider != null) {
                scannedFilePaths
                        = projectFiles.stream()
                                .filter(file -> GraphParser.matchesExtension(file, provider.extensions()))
                                .toList();
            } else {
                scannedFilePaths = parser.scannedFilePaths(projectRoot, projectFiles);
            }
            return new ParserTask(
                    parser,
                    scannedFilePaths,
                    new AtomicReference<>(
                            provider == null
                                    ? GraphParser.relativeFileNames(projectRoot, scannedFilePaths) : List.of()),
                    null);
        } catch (RuntimeException e) {
            return new ParserTask(parser, List.of(), new AtomicReference<>(List.of()), e);
        }
    }

    private List<String> collectWarnings(GraphParser parser) {
        try {
            return parser.warnings();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private ProjectGraph deltaFrom(ProjectGraph fragment, ProjectGraph baseGraph) {
        ProjectGraph delta = new ProjectGraph();
        Set<GraphEdge> baseEdges = baseGraph.getEdgeSet();

        fragment.getNodes().values().stream()
                .filter(node -> !Objects.equals(baseGraph.getNode(node.id()), node))
                .forEach(delta::addNode);

        fragment.getEdges().stream()
                .filter(edge -> !baseEdges.contains(edge))
                .forEach(delta::addEdge);

        return delta;
    }

    private ParserDiagnostic warnOnNodeConflicts(
            String provider, Map<String, String> owners, ParserRun run, ProjectGraph merged) {
        List<String> conflicts = new ArrayList<>();
        for (GraphNode node : run.graph().getNodes().values()) {
            String previous = owners.put(node.id(), provider);
            if (previous != null && !node.equals(merged.getNode(node.id()))) {
                conflicts.add("Node " + node.id() + " from " + provider + " replaces the node from " + previous);
            }
        }
        var diagnostic = run.diagnostic();
        if (conflicts.isEmpty()) {
            return diagnostic;
        }
        conflicts.sort(String::compareTo);
        var warnings = new ArrayList<>(diagnostic.warnings());
        warnings.addAll(conflicts);
        return new ParserDiagnostic(
                diagnostic.parserName(), diagnostic.scannedFiles(), diagnostic.skippedFiles(),
                warnings, diagnostic.failures(), diagnostic.timedOut(), diagnostic.nodesProduced(),
                diagnostic.edgesProduced(), diagnostic.durationMillis());
    }

    private void mergeDeterministically(ProjectGraph target, ProjectGraph source) {
        source.getNodes().values().stream()
                .sorted(Comparator.comparing(GraphNode::id))
                .forEach(target::addNode);
        source.getEdges().stream()
                .sorted(GraphBuilder::compareEdges)
                .forEach(target::addEdge);
    }

    private static int compareEdges(GraphEdge left, GraphEdge right) {
        return Comparator.comparing(GraphEdge::from)
                .thenComparing(GraphEdge::to)
                .thenComparing(edge -> edge.type().name())
                .thenComparing(edge -> sortedProperties(edge.properties()))
                .compare(left, right);
    }

    private static String sortedProperties(Map<String, String> properties) {
        return properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList()
                .toString();
    }

    private ParserDiagnostic failureDiagnostic(
            GraphParser parser, List<String> scannedFiles, Throwable failure,
            long durationMillis) {
        String message = failure == null || failure.getMessage() == null
                ? "Parser failed"
                : failure.getMessage();
        return diagnostic(
                parserName(parser),
                scannedFiles,
                List.of(),
                collectWarnings(parser),
                List.of(message),
                false,
                0,
                0,
                durationMillis);
    }

    private ParserDiagnostic failureDiagnostic(
            String parserName, List<String> scannedFiles, Throwable failure,
            long durationMillis) {
        String message = failure == null || failure.getMessage() == null
                ? "Parser failed"
                : failure.getMessage();
        return diagnostic(
                parserName,
                scannedFiles,
                List.of(),
                List.of(),
                List.of(message),
                false,
                0,
                0,
                durationMillis);
    }

    private ParserDiagnostic interruptedDiagnostic(GraphParser parser, List<String> scannedFiles) {
        return diagnostic(
                parser,
                scannedFiles,
                List.of(),
                List.of(),
                List.of("Parser execution was interrupted"),
                false,
                0,
                0,
                0);
    }

    private void addSkippedAfterInterruptionDiagnostics(
            List<ParserFuture> futures, int startIndex, List<ParserDiagnostic> diagnostics) {
        for (int i = startIndex; i < futures.size(); i++) {
            ParserFuture parserFuture = futures.get(i);
            parserFuture.future().cancel(true);
            diagnostics.add(skippedAfterInterruptionDiagnostic(
                    parserFuture.task().parser(),
                    parserFuture.task().scannedFiles()));
        }
    }

    private ParserDiagnostic skippedAfterInterruptionDiagnostic(GraphParser parser, List<String> scannedFiles) {
        return diagnostic(
                parser,
                scannedFiles,
                scannedFiles,
                List.of(),
                List.of("Parser execution skipped after graph build interruption"),
                false,
                0,
                0,
                0);
    }

    private ParserDiagnostic timeoutDiagnostic(GraphParser parser, List<String> scannedFiles) {
        return diagnostic(
                parser,
                scannedFiles,
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                parserTimeoutUnit.toMillis(parserTimeout));
    }

    private ParserDiagnostic diagnostic(
            GraphParser parser,
            List<String> scannedFiles,
            List<String> skippedFiles,
            List<String> warnings,
            List<String> failures,
            boolean timedOut,
            int nodesAdded,
            int edgesAdded,
            long durationMillis) {
        return diagnostic(
                parserName(parser),
                scannedFiles,
                skippedFiles,
                warnings,
                failures,
                timedOut,
                nodesAdded,
                edgesAdded,
                durationMillis);
    }

    private ParserDiagnostic diagnostic(
            String parserName,
            List<String> scannedFiles,
            List<String> skippedFiles,
            List<String> warnings,
            List<String> failures,
            boolean timedOut,
            int nodesAdded,
            int edgesAdded,
            long durationMillis) {
        return new ParserDiagnostic(
                parserName,
                scannedFiles,
                skippedFiles,
                warnings,
                failures,
                timedOut,
                nodesAdded,
                edgesAdded,
                durationMillis);
    }

    private String parserName(GraphParser parser) {
        String simpleName = parser.getClass().getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            return parser.getClass().getName();
        }
        return simpleName;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record ParserTask(
            GraphParser parser,
            List<Path> scannedFilePaths,
            AtomicReference<List<String>> selectedFiles,
            RuntimeException preparationFailure) {
        List<String> scannedFiles() {
            return selectedFiles.get();
        }
    }

    private record MigrationProvider(String name, Set<String> extensions) {
    }

    private record ParserFuture(ParserTask task, Future<ParserRun> future, long submittedAt) {
    }

    private record ParserRun(ProjectGraph graph, ParserDiagnostic diagnostic) {
    }
}
