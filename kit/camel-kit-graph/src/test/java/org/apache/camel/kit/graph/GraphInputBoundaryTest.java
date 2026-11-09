package org.apache.camel.kit.graph;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.model.NodeType;
import org.apache.camel.kit.graph.parser.GraphParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GraphInputBoundaryTest {

    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;

    @Test
    void selectedRootMayBeASymbolicLink(@TempDir Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("real"));
        Files.writeString(root.resolve("application.properties"), "inside.canary=ordinary-value\n");
        Path alias = Files.createSymbolicLink(parent.resolve("selected"), root);
        assertTrue(new GraphBuilder().build(alias).hasNode("config:inside.canary"));
    }

    @Test
    void aggregateSourceLimitIsCheckedBeforeParsing(@TempDir Path root) throws Exception {
        for (int i = 0; i < 17; i++) {
            try (var file
                    = new java.io.RandomAccessFile(root.resolve("application-" + i + ".properties").toFile(), "rw")) {
                file.setLength(MAX_SOURCE_BYTES);
            }
        }
        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);
        assertFalse(result.successful());
        assertTrue(result.failureSummary().contains("Project source exceeds"));
        assertEquals(0, result.graph().nodeCount());
    }

    @Test
    void aggregateBudgetAcceptsLargeSourceTreesBeforeRejectingOverflow(@TempDir Path root) throws Exception {
        for (int i = 0; i < 16; i++) {
            try (var file = new java.io.RandomAccessFile(root.resolve("part-" + i + ".java").toFile(), "rw")) {
                file.setLength(MAX_SOURCE_BYTES);
            }
        }
        assertEquals(16, GraphParser.projectFiles(root).size());
        Files.writeString(root.resolve("overflow.java"), "x");
        assertThrows(IllegalArgumentException.class, () -> GraphParser.projectFiles(root));
    }

    @Test
    void discoveryPrunesVcsBuildAndDependencyTrees(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(source.resolve("application.properties"), "source.canary=present");
        for (String ignored : List.of(".git", ".svn", ".hg", "target", "build", "node_modules", "nested/target")) {
            Path dir = Files.createDirectories(root.resolve(ignored));
            Files.writeString(dir.resolve("application.properties"), "duplicate.canary=absent");
            try (var file = new java.io.RandomAccessFile(dir.resolve("oversized.xml").toFile(), "rw")) {
                file.setLength(MAX_SOURCE_BYTES + 1L);
            }
        }
        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);
        assertTrue(result.successful(), result::failureSummary);
        assertTrue(result.graph().hasNode("config:source.canary"));
        assertFalse(result.graph().hasNode("config:duplicate.canary"));
        assertEquals(List.of(source.resolve("application.properties")), GraphParser.projectFiles(root));
    }

    @Test
    void xmlSniffingValidatesCandidatesButDoesNotInspectUnrelatedSources(@TempDir Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("project"));
        Path outside = Files.writeString(parent.resolve("outside.xml"),
                "<mule xmlns='http://www.mulesoft.org/schema/mule/core'/>");
        Path linked = Files.createSymbolicLink(root.resolve("linked.xml"), outside);
        Path oversized = root.resolve("oversized.xml");
        try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(MAX_SOURCE_BYTES + 1L);
        }
        for (GraphParser parser : List.of(new org.apache.camel.kit.graph.parser.XmlRouteParser(),
                new org.apache.camel.kit.graph.parser.MuleXmlFlowParser(),
                new org.apache.camel.kit.graph.parser.BizTalkParser())) {
            for (Path invalid : List.of(outside, linked, oversized)) {
                assertThrows(IllegalArgumentException.class, () -> parser.scannedFilePaths(root, List.of(invalid)));
            }
            assertEquals(List.of(), parser.scannedFilePaths(root, List.of(parent.resolve("unrelated.java"))));
        }
    }

    @Test
    void suppliedFileListsCannotBypassConfinementOrSizeBounds(@TempDir Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("project"));
        Path outside = Files.writeString(parent.resolve("outside.properties"), "secret=value");
        Path oversized = root.resolve("oversized.properties");
        try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(MAX_SOURCE_BYTES + 1L);
        }
        List<GraphParser> parsers = List.of(
                new org.apache.camel.kit.graph.parser.PomParser(),
                new org.apache.camel.kit.graph.parser.JavaGraphParser(),
                new org.apache.camel.kit.graph.parser.GroovyGraphParser(),
                new org.apache.camel.kit.graph.parser.XmlRouteParser(),
                new org.apache.camel.kit.graph.parser.MuleXmlFlowParser(),
                new org.apache.camel.kit.graph.parser.DataWeaveParser(),
                new org.apache.camel.kit.graph.parser.BizTalkParser(),
                new org.apache.camel.kit.graph.parser.YamlRouteParser(),
                new org.apache.camel.kit.graph.parser.ConfigParser());
        for (GraphParser parser : parsers) {
            assertThrows(IllegalArgumentException.class,
                    () -> parser.parseFiles(root, List.of(outside), new ProjectGraph()));
            assertThrows(IllegalArgumentException.class,
                    () -> parser.parseFiles(root, List.of(oversized), new ProjectGraph()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> GraphParser.requireSafeFiles(root, java.util.Collections.nCopies(100_001, oversized)));
    }

    @Test
    void suppliedPathsCannotHideAnOutsideSymlinkBehindAParentSegment(@TempDir Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("project"));
        Path outside = Files.createDirectory(parent.resolve("outside"));
        Path outsideChild = Files.createDirectory(outside.resolve("child"));
        Files.writeString(root.resolve("application.properties"), "inside.canary=ordinary-value\n");
        Files.writeString(outside.resolve("application.properties"), "outside.canary=private-value\n");
        Path link = Files.createSymbolicLink(root.resolve("link"), outsideChild);
        Path input = link.resolve("../application.properties");
        assertEquals(root.resolve("application.properties"), input.normalize());
        assertEquals(outside.resolve("application.properties"), input.toRealPath());
        ProjectGraph graph = new ProjectGraph();

        assertThrows(IllegalArgumentException.class,
                () -> new org.apache.camel.kit.graph.parser.ConfigParser().parseFiles(root, List.of(input), graph));
        assertFalse(graph.hasNode("config:outside.canary"));
    }

    @Test
    void outsideRootFileSymlinkCannotIntroducePrivateProperties(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Path sibling = Files.createDirectory(tempDir.resolve("project-other"));
        Path outside = Files.writeString(sibling.resolve("application.properties"), "outside.canary=private-value\n");
        Files.writeString(root.resolve("application-local.properties"), "inside.canary=ordinary-value\n");
        Files.createSymbolicLink(root.resolve("application.properties"), root.relativize(outside));

        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);

        assertFalse(result.graph().hasNode("config:outside.canary"),
                "An input filename inside the project must not authorize reading its outside symlink target");
        assertTrue(result.graph().getNodes().values().stream()
                .noneMatch(node -> node.properties().containsValue("private-value")));
    }

    @Test
    void directorySymlinkCannotIntroduceOutsideProperties(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("application.properties"), "outside.canary=private-value\n");
        Files.createSymbolicLink(root.resolve("linked-directory"), outside);

        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);

        assertFalse(result.graph().hasNode("config:outside.canary"));
    }

    @Test
    void ordinaryInputStillProducesItsGraph(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("application.properties"), "inside.canary=ordinary-value\n");

        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);

        assertTrue(result.successful(), result::failureSummary);
        assertEquals("ordinary-value", result.graph().getNode("config:inside.canary").properties().get("value"));
    }

    @Test
    void pomParserUsesTheSameTimeoutAndCannotPublishItsPartialGraph(@TempDir Path root) {
        GraphBuilder builder = new GraphBuilder(new SlowPomParser(), List.of(), 20, TimeUnit.MILLISECONDS);

        GraphBuildResult result = builder.buildWithDiagnostics(root);
        ParserDiagnostic pom = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.parserName().equals("SlowPomParser"))
                .findFirst().orElseThrow();

        assertTrue(pom.timedOut(), "The first parser must obey the configured parser budget too");
        assertFalse(result.successful());
        assertFalse(result.graph().hasNode("maven:partial:timeout"),
                "A cancelled parser cannot publish partial results even if it returns after interruption");
    }

    @Test
    void laterParserCannotReceiveAFreshBudgetAfterTheFirstTimesOut(@TempDir Path root) {
        CountDownLatch firstCancelled = new CountDownLatch(1);
        GraphParser first = (project, graph) -> {
            try {
                firstCancelled.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                firstCancelled.countDown();
            }
        };
        GraphParser second = (project, graph) -> {
            try {
                firstCancelled.await(5, TimeUnit.SECONDS);
                // Complete after the first timeout, still inside the old, incorrectly renewed budget.
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            graph.addNode(new GraphNode("class:over-budget", NodeType.CLASS, Map.of()));
        };
        GraphBuilder builder = new GraphBuilder((project, graph) -> {
        }, List.of(first, second),
                200, TimeUnit.MILLISECONDS);

        GraphBuildResult result = builder.buildWithDiagnostics(root);

        assertTrue(result.diagnostics().get(1).timedOut());
        assertTrue(result.diagnostics().get(2).timedOut(), "Waiting for another parser must not renew this budget");
        assertFalse(result.graph().hasNode("class:over-budget"));
    }

    @Test
    void sourceAtTheDocumentedLimitIsAccepted(@TempDir Path root) throws Exception {
        Path source = root.resolve("application.properties");
        writeProperties(source, MAX_SOURCE_BYTES);

        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);

        assertEquals(MAX_SOURCE_BYTES, Files.size(source));
        assertTrue(result.successful(), result::failureSummary);
        assertEquals("ordinary-value", result.graph().getNode("config:inside.canary").properties().get("value"));
    }

    @Test
    void sourceOneByteOverTheLimitFailsWithoutPublishingItsContents(@TempDir Path root) throws Exception {
        Path source = root.resolve("application.properties");
        writeProperties(source, MAX_SOURCE_BYTES + 1);

        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);

        assertEquals(MAX_SOURCE_BYTES + 1L, Files.size(source));
        assertFalse(result.successful(), "Oversized input must produce a diagnostic instead of being parsed");
        assertFalse(result.graph().hasNode("config:inside.canary"));
        assertFalse(result.failedDiagnostics().isEmpty());
    }

    @Test
    void unrelatedLargeArtifactDoesNotPreventParsingSources(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("application.properties"), "inside.canary=ordinary-value\n");
        try (FileChannel artifact = FileChannel.open(root.resolve("application.jar"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            artifact.position(MAX_SOURCE_BYTES);
            artifact.write(ByteBuffer.wrap(new byte[]{0}));
        }

        GraphBuildResult result = new GraphBuilder().buildWithDiagnostics(root);

        assertTrue(result.successful(), result::failureSummary);
        assertEquals("ordinary-value", result.graph().getNode("config:inside.canary").properties().get("value"));
    }

    private static void writeProperties(Path file, int bytes) throws IOException {
        byte[] property = "inside.canary=ordinary-value\n".getBytes(StandardCharsets.UTF_8);
        byte[] comment = new byte[8192];
        Arrays.fill(comment, (byte) ' ');
        comment[0] = '#';
        comment[comment.length - 1] = '\n';
        try (OutputStream output = Files.newOutputStream(file)) {
            output.write(property);
            int remaining = bytes - property.length;
            while (remaining > 0) {
                int length = Math.min(remaining, comment.length);
                output.write(comment, 0, length);
                remaining -= length;
            }
        }
    }

    private static final class SlowPomParser implements GraphParser {
        @Override
        public void parse(Path root, ProjectGraph graph) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            graph.addNode(new GraphNode("maven:partial:timeout", NodeType.MAVEN_ARTIFACT, Map.of()));
        }
    }
}
