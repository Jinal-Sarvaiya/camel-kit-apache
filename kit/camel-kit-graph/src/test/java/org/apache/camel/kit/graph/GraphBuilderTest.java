package org.apache.camel.kit.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.camel.kit.graph.model.*;
import org.apache.camel.kit.graph.parser.GraphParser;
import org.apache.camel.kit.graph.parser.MuleXmlFlowParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GraphBuilderTest {

    private static final Path TEST_PROJECT = Path.of("src/test/resources/testdata");

    @Test
    void buildsGraphFromTestProject() {
        ProjectGraph graph = new GraphBuilder().build(TEST_PROJECT);
        assertFalse(graph.findByType(NodeType.CLASS).isEmpty());
        assertFalse(graph.findByType(NodeType.CAMEL_ROUTE).isEmpty());
        assertFalse(graph.findByType(NodeType.CAMEL_ENDPOINT).isEmpty());
        assertFalse(graph.findByType(NodeType.MAVEN_ARTIFACT).isEmpty());
        assertFalse(graph.findByType(NodeType.CONFIG_PROPERTY).isEmpty());
    }

    @Test
    void buildWithDiagnosticsReportsParserScans() {
        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(TEST_PROJECT);

        assertFalse(result.graph().findByType(NodeType.CLASS).isEmpty());
        ParserDiagnostic pomDiagnostic = findDiagnostic(result, "PomParser");
        assertEquals(List.of("di/pom.xml", "pom.xml"), pomDiagnostic.scannedFiles());
        assertTrue(pomDiagnostic.successful());

        ParserDiagnostic bizTalkDiagnostic = findDiagnostic(result, "BizTalkParser");
        assertTrue(bizTalkDiagnostic.warnings().stream()
                .anyMatch(warning -> warning.contains("malformed.odx")));
    }

    @Test
    void crossLinkerRuns() {
        ProjectGraph graph = new GraphBuilder().build(TEST_PROJECT);
        long linksToCount = graph.getEdges().stream()
                .filter(e -> e.type().equals(EdgeType.LINKS_TO))
                .count();
        assertTrue(linksToCount >= 1, "Expected at least one LINKS_TO edge");
    }

    @Test
    void buildAndSerialize(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve(".camel-kit").resolve("project-graph.json");
        new GraphBuilder().buildAndSerialize(TEST_PROJECT, outputFile);
        assertTrue(Files.exists(outputFile));
        assertTrue(outputFile.toFile().length() > 100);
    }

    @Test
    void emptyProjectProducesEmptyGraph(@TempDir Path emptyDir) {
        ProjectGraph graph = new GraphBuilder().build(emptyDir);
        assertEquals(0, graph.nodeCount());
        assertEquals(0, graph.edgeCount());
    }

    @Test
    void buildsPropertyBindingEdges() {
        ProjectGraph graph = new GraphBuilder().build(Path.of("src/test/resources/testdata/di"));
        var edges = graph.getEdges().stream()
                .filter(e -> e.type().equals(EdgeType.INSTANTIATES) || e.type().equals(EdgeType.REFERENCES_BEAN))
                .toList();
        assertFalse(edges.isEmpty(),
                "GraphBuilder should run PropertyBindingParser to create INSTANTIATES/REFERENCES_BEAN edges");
    }

    @Test
    void buildsInterfaceExpansionEdges() {
        ProjectGraph graph = new GraphBuilder().build(Path.of("src/test/resources/testdata/di"));
        var edges = graph.getEdges().stream()
                .filter(e -> e.type().equals(EdgeType.DEPENDS_ON_VIA_INTERFACE))
                .toList();
        assertFalse(edges.isEmpty(),
                "GraphBuilder should run CrossLinker.expandInterfaces to create DEPENDS_ON_VIA_INTERFACE edges");
    }

    @Test
    void parserFailuresAreCapturedAsDiagnostics(@TempDir Path tempDir) {
        GraphBuilder builder = new GraphBuilder(
                new EmptyParser(),
                List.of(new FailingParser()),
                1,
                TimeUnit.SECONDS);

        GraphBuildResult result = builder.buildWithDiagnostics(tempDir);

        ParserDiagnostic diagnostic = findDiagnostic(result, "FailingParser");
        assertFalse(diagnostic.failures().isEmpty());
        assertTrue(diagnostic.failures().get(0).contains("intentional parser failure"));
        assertFalse(result.graph().hasNode("class:partial"),
                "Failed parser fragments should not be merged into the final graph");
    }

    @Test
    void buildThrowsWhenDiagnosticsFail(@TempDir Path tempDir) {
        GraphBuilder builder = new GraphBuilder(
                new EmptyParser(),
                List.of(new FailingParser()),
                1,
                TimeUnit.SECONDS);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> builder.build(tempDir));
        assertTrue(error.getMessage().contains("FailingParser"));
    }

    @Test
    void parserTimeoutsAreCapturedAsDiagnostics(@TempDir Path tempDir) {
        GraphBuilder builder = new GraphBuilder(
                new EmptyParser(),
                List.of(new SleepingParser()),
                20,
                TimeUnit.MILLISECONDS);

        GraphBuildResult result = builder.buildWithDiagnostics(tempDir);

        ParserDiagnostic diagnostic = findDiagnostic(result, "SleepingParser");
        assertTrue(diagnostic.timedOut());
        assertEquals(List.of("slow.file"), diagnostic.scannedFiles());
    }

    @Test
    void unexpectedParserErrorsKeepElapsedDuration(@TempDir Path tempDir) {
        GraphBuilder builder = new GraphBuilder(
                new EmptyParser(),
                List.of(new ErrorParser()),
                1,
                TimeUnit.SECONDS);

        GraphBuildResult result = builder.buildWithDiagnostics(tempDir);

        ParserDiagnostic diagnostic = findDiagnostic(result, "ErrorParser");
        assertFalse(diagnostic.failures().isEmpty());
        assertTrue(diagnostic.durationMillis() > 0);
    }

    @Test
    void interruptionReportsRemainingParsersAsSkipped(@TempDir Path tempDir) {
        GraphBuilder builder = new GraphBuilder(
                new EmptyParser(),
                List.of(new SleepingParser(), new SkippedParser()),
                1,
                TimeUnit.SECONDS);

        GraphBuildResult result;
        Thread.currentThread().interrupt();
        try {
            result = builder.buildWithDiagnostics(tempDir);
        } finally {
            Thread.interrupted();
        }

        ParserDiagnostic interrupted = findDiagnostic(result, "SleepingParser");
        ParserDiagnostic skipped = findDiagnostic(result, "SkippedParser");
        assertFalse(interrupted.failures().isEmpty());
        assertFalse(skipped.failures().isEmpty());
        assertEquals(List.of("skipped.file"), skipped.scannedFiles());
        assertEquals(List.of("skipped.file"), skipped.skippedFiles());
    }

    @Test
    void parserFragmentsMergeInConfiguredOrder(@TempDir Path tempDir) {
        GraphBuilder builder = new GraphBuilder(
                new EmptyParser(),
                List.of(new OrderedEdgeParser("first", 50), new OrderedEdgeParser("second", 0)),
                1,
                TimeUnit.SECONDS);

        GraphBuildResult result = builder.buildWithDiagnostics(tempDir);

        List<String> edgeSources = result.graph().getEdges().stream()
                .filter(edge -> edge.type().equals(EdgeType.CALLS))
                .map(GraphEdge::from)
                .toList();
        assertEquals(List.of("class:first", "class:second"), edgeSources);
    }

    @Test
    void builderUsesIndexedScannedFilesForParserExecution(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("indexed.file"), "content");
        GraphBuilder builder = new GraphBuilder(
                new EmptyParser(),
                List.of(new IndexedParser()),
                1,
                TimeUnit.SECONDS);

        GraphBuildResult result = builder.buildWithDiagnostics(tempDir);

        ParserDiagnostic diagnostic = findDiagnostic(result, "IndexedParser");
        assertTrue(diagnostic.successful());
        assertEquals(List.of("indexed.file"), diagnostic.scannedFiles());
        assertTrue(result.graph().hasNode("class:indexed.file"));
    }

    @Test
    void migrationParserReceivesOnlyMatchingFilesAndKeepsPartialDiagnostics(@TempDir Path root) throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        String mule = "<mule xmlns='http://www.mulesoft.org/schema/mule/core'>";
        Files.writeString(src.resolve("ordinary.xml"), "<other/>");
        Files.writeString(src.resolve("good.xml"), mule + "<flow name='orders'/></mule>");
        Files.writeString(src.resolve("bad.xml"), mule);
        GraphBuilder builder = new GraphBuilder(Map.of("mule-xml", new MuleXmlFlowParser()));
        GraphBuildResult result = builder.buildWithDiagnostics(root);
        ParserDiagnostic diagnostic = findDiagnostic(result, "MuleXmlFlowParser");
        assertTrue(result.successful(), result::failureSummary);
        assertEquals(List.of("src/bad.xml", "src/good.xml"), diagnostic.scannedFiles());
        assertEquals(1, diagnostic.warnings().size());
        assertTrue(diagnostic.warnings().getFirst().contains("src/bad.xml"));
        assertEquals(1, diagnostic.nodesProduced());
        assertEquals("src/good.xml", result.graph().getNode("mule-flow:orders").properties().get("file"));

        Files.delete(src.resolve("bad.xml"));
        Files.delete(src.resolve("good.xml"));
        var empty = builder.buildWithDiagnostics(root);
        assertTrue(empty.successful(), empty::failureSummary);
        assertEquals(List.of(), findDiagnostic(empty, "MuleXmlFlowParser").scannedFiles());
        assertTrue(empty.warningDiagnostics().isEmpty());
        assertEquals(0, empty.graph().nodeCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "XML", ".xml", "dir/xml", "dir\\xml"})
    void rejectsInvalidExtensionsWithTheProviderId(String extension) {
        assertInvalidExtensions(Set.of(extension));
    }

    @Test
    void rejectsMissingExtensionsWithTheProviderId() {
        assertInvalidExtensions(null);
        assertInvalidExtensions(Set.of());
        assertInvalidExtensions(Collections.singleton(null));
    }

    private void assertInvalidExtensions(Set<String> declared) {
        var parser = new MuleXmlFlowParser() {
            @Override
            public Set<String> inputExtensions() {
                return declared;
            }
        };
        var error = assertThrows(IllegalArgumentException.class, () -> new GraphBuilder(Map.of("inventory", parser)));
        assertTrue(error.getMessage().contains("provider/inventory"), error::getMessage);
        assertTrue(error.getMessage().contains("input extensions"), error::getMessage);
    }

    @Test
    void rejectsOneParserInstanceRegisteredUnderTwoIds() {
        var parser = new MuleXmlFlowParser();
        var error = assertThrows(IllegalArgumentException.class,
                () -> new GraphBuilder(Map.of("first", parser, "second", parser)));
        assertTrue(error.getMessage().contains("provider/first") && error.getMessage().contains("provider/second"),
                error::getMessage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migrationTimeoutReportsOnlySelectedInputs(boolean duringSelection, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("flow.xml"), "<mule xmlns='http://www.mulesoft.org/schema/mule/core'/>");
        var phaseReached = new CompletableFuture<Void>();
        var parser = new MuleXmlFlowParser() {
            @Override
            public boolean acceptsContent(Path file) {
                if (duringSelection) {
                    awaitCancellation();
                }
                return super.acceptsContent(file);
            }

            @Override
            public void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph) {
                awaitCancellation();
            }

            private void awaitCancellation() {
                phaseReached.complete(null);
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
            }
        };
        var builder = new GraphBuilder(new EmptyParser(), List.of(parser), 1, TimeUnit.MINUTES) {
            @Override
            long remainingParserNanos(long submittedAt) {
                // Expire the deadline only after the worker reaches the phase under test.
                phaseReached.orTimeout(1, TimeUnit.MINUTES).join();
                return 0;
            }
        };
        var diagnostic = findDiagnostic(builder.buildWithDiagnostics(root), parser.getClass().getName());
        assertTrue(diagnostic.timedOut());
        assertEquals(duringSelection ? List.of() : List.of("flow.xml"), diagnostic.scannedFiles());
    }

    @Test
    void failedContentCheckDoesNotReportCandidatesAsParsed(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("other.xml"), "<other/>");
        var parser = new MuleXmlFlowParser() {
            @Override
            public boolean acceptsContent(Path file) {
                throw new IllegalStateException("could not select inputs");
            }
        };
        var result = new GraphBuilder(Map.of("inventory", parser)).buildWithDiagnostics(root);
        ParserDiagnostic diagnostic = result.failedDiagnostics().getFirst();
        assertEquals(List.of("could not select inputs"), diagnostic.failures());
        assertEquals(List.of(), diagnostic.scannedFiles());
    }

    @Test
    void differentProviderNodesWithTheSameIdWarnAndKeepCatalogOrder(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("input.xml"), "<mule xmlns='http://www.mulesoft.org/schema/mule/core'/>");
        var builder = new GraphBuilder(Map.of("last", new NodeProvider("last"), "first", new NodeProvider("first")));
        var result = builder.buildWithDiagnostics(root);
        assertTrue(result.successful(), result::failureSummary);
        assertEquals("last", result.graph().getNode("shared").properties().get("value"));
        var warnings = result.warningDiagnostics().getFirst().warnings();
        assertEquals(List.of("Node shared from provider/last replaces the node from provider/first"), warnings);
    }

    private static final class NodeProvider extends MuleXmlFlowParser {
        private final String value;

        private NodeProvider(String value) {
            this.value = value;
        }

        @Override
        public void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph) {
            graph.addNode(new GraphNode("shared", NodeType.RESOURCE_FILE, Map.of("value", value)));
            graph.addNode(new GraphNode("identical", NodeType.RESOURCE_FILE, Map.of()));
        }
    }

    private ParserDiagnostic findDiagnostic(GraphBuildResult result, String parserName) {
        return result.diagnostics().stream()
                .filter(diagnostic -> parserName.equals(diagnostic.parserName()))
                .findFirst()
                .orElseThrow();
    }

    private static final class EmptyParser implements GraphParser {

        @Override
        public void parse(Path projectRoot, ProjectGraph graph) {
        }
    }

    private static final class FailingParser implements GraphParser {

        @Override
        public void parse(Path projectRoot, ProjectGraph graph) {
            graph.addNode(new GraphNode("class:partial", NodeType.CLASS, Map.of()));
            throw new IllegalStateException("intentional parser failure");
        }
    }

    private static final class SleepingParser implements GraphParser {

        @Override
        public void parse(Path projectRoot, ProjectGraph graph) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public List<String> scannedFiles(Path projectRoot) {
            return List.of("slow.file");
        }
    }

    private static final class ErrorParser implements GraphParser {

        @Override
        public void parse(Path projectRoot, ProjectGraph graph) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("intentional parser error");
        }
    }

    private static final class SkippedParser implements GraphParser {

        @Override
        public void parse(Path projectRoot, ProjectGraph graph) {
        }

        @Override
        public List<String> scannedFiles(Path projectRoot) {
            return List.of("skipped.file");
        }
    }

    private static final class OrderedEdgeParser implements GraphParser {

        private final String name;
        private final long delayMillis;

        private OrderedEdgeParser(String name, long delayMillis) {
            this.name = name;
            this.delayMillis = delayMillis;
        }

        @Override
        public void parse(Path projectRoot, ProjectGraph graph) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String classNodeId = "class:" + name;
            graph.addNode(new GraphNode(classNodeId, NodeType.CLASS, Map.of("name", name)));
            graph.addNode(new GraphNode("class:target", NodeType.CLASS, Map.of()));
            graph.addEdge(new GraphEdge(classNodeId, "class:target", EdgeType.CALLS, Map.of()));
        }
    }

    private static final class IndexedParser implements GraphParser {

        @Override
        public void parse(Path projectRoot, ProjectGraph graph) {
            throw new AssertionError("GraphBuilder should call parseFiles with indexed candidates");
        }

        @Override
        public List<String> scannedFiles(Path projectRoot) {
            throw new AssertionError("GraphBuilder should use scannedFilePaths from the project file index");
        }

        @Override
        public List<Path> scannedFilePaths(Path projectRoot, List<Path> projectFiles) {
            return projectFiles.stream()
                    .filter(file -> file.getFileName().toString().equals("indexed.file"))
                    .toList();
        }

        @Override
        public void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph) {
            files.forEach(file -> graph.addNode(new GraphNode(
                    "class:" + GraphParser.relativeFileName(projectRoot, file),
                    NodeType.CLASS,
                    Map.of())));
        }
    }
}
