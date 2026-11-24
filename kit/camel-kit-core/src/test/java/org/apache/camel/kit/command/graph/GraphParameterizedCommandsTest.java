package org.apache.camel.kit.command.graph;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.camel.kit.CommandRunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class GraphParameterizedCommandsTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir
    Path tempDir;
    Path graphFile;

    @BeforeEach
    void setUp() throws Exception {
        graphFile = TestGraphs.writeToTempFile(TestGraphs.sampleProject(), tempDir);
    }

    @Test
    void findByType() throws Exception {
        JsonNode r = MAPPER.readTree(run(new GraphFindCommand(), "--type", "CAMEL_ROUTE"));
        assertTrue(r.get("found").asBoolean());
        assertEquals(2, r.get("total").asInt());
    }

    @ParameterizedTest
    @CsvSource({
            "mule, MULE_FLOW, mule:flow, MULE_CALLS_SUBFLOW, mule:calls-subflow",
            "biztalk, BIZTALK_ORCHESTRATION, biztalk:orchestration, BIZTALK_USES_MAP, biztalk:uses-map"
    })
    void legacyGraphAndFiltersUseTheBundledAdapter(
            String adapter, String oldNode, String nodeType,
            String oldEdge, String edgeType)
            throws Exception {
        Files.writeString(graphFile, String.format(Locale.ROOT, """
                {"version":"1.0","nodes":{"a":{"type":"%s"},"b":{"type":"RESOURCE_FILE"}},
                 "edges":[{"from":"a","to":"b","type":"%s"}]}
                """, oldNode, oldEdge));
        var found = MAPPER.readTree(run(new GraphFindCommand(), "--type", oldNode)).required("nodes");
        assertEquals(nodeType, found.required(0).required("type").textValue());
        assertEquals(found, MAPPER.readTree(run(new GraphFindCommand(), "--type", nodeType)).required("nodes"));
        var neighbors = MAPPER.readTree(run(new GraphNeighborsCommand(), "a", "--edge-type", oldEdge));
        assertEquals(edgeType, neighbors.required("edges").required(0).required("type").textValue());
        assertEquals("b", neighbors.required("nodes").required(0).required("id").textValue());
        assertTrue(MAPPER.readTree(run(new GraphAnalyzeCommand(), adapter)).required("matched").booleanValue());
    }

    @Test
    void findWithPattern() throws Exception {
        JsonNode r = MAPPER.readTree(run(new GraphFindCommand(), "--type", "CAMEL_ROUTE", "--name", ".*process.*"));
        assertEquals(1, r.get("total").asInt());
    }

    @Test
    void neighborsReturnsConnected() throws Exception {
        JsonNode r = MAPPER.readTree(run(new GraphNeighborsCommand(), "route:order-process"));
        assertTrue(r.get("found").asBoolean());
        assertTrue(r.has("nodes"));
        assertTrue(r.has("edges"));
    }

    @Test
    void pathBetweenNodes() throws Exception {
        JsonNode r = MAPPER.readTree(run(new GraphPathCommand(), "route:order-process", "endpoint:kafka:orders-out"));
        assertTrue(r.get("found").asBoolean());
        assertTrue(r.get("length").asInt() > 0);
    }

    @Test
    void subgraphReturnsNodesAndEdges() throws Exception {
        JsonNode r = MAPPER.readTree(run(new GraphSubgraphCommand(), "route:order-process"));
        assertTrue(r.has("nodes"));
        assertTrue(r.has("edges"));
        assertTrue(r.get("nodes").size() > 0);
    }

    @Test
    void routeFlowTraces() throws Exception {
        JsonNode r = MAPPER.readTree(run(new GraphRouteFlowCommand(), "route:order-process"));
        assertTrue(r.get("found").asBoolean());
        assertTrue(r.has("steps"));
    }

    @Test
    void impactReturnsGroupedByType() throws Exception {
        JsonNode r = MAPPER.readTree(run(new GraphImpactCommand(), "route:order-process"));
        assertTrue(r.get("found").asBoolean());
        assertTrue(r.has("byType"));
    }

    private String run(GraphQueryCommand cmd, String... args) {
        StringWriter out = new StringWriter(), err = new StringWriter();
        CommandRunner cl = new CommandRunner(cmd);
        cl.setOut(new PrintWriter(out));
        cl.setErr(new PrintWriter(err));
        String[] full = new String[args.length + 2];
        full[0] = "--graph-file";
        full[1] = graphFile.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        assertEquals(0, cl.execute(full), "Failed: " + err);
        return out.toString().trim();
    }
}
