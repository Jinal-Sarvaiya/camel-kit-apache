package org.apache.camel.kit.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.graph.adapter.biztalk.BizTalkGraphAdapter;
import org.apache.camel.kit.graph.adapter.mule.MuleGraphAdapter;
import org.apache.camel.kit.graph.model.*;
import org.apache.camel.kit.graph.query.GraphQuery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GraphSerializerTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTrip() throws IOException {
        ProjectGraph original = new ProjectGraph();
        original.addNode(new GraphNode(
                "class:com.example.Foo", NodeType.CLASS,
                Map.of("name", "Foo", "package", "com.example")));
        original.addNode(new GraphNode(
                "route:processOrders", NodeType.CAMEL_ROUTE,
                Map.of("routeId", "processOrders", "fromUri", "kafka:orders")));
        original.addEdge(new GraphEdge(
                "class:com.example.Foo", "route:processOrders",
                EdgeType.DECLARES, Map.of()));

        Path file = tempDir.resolve("project-graph.json");
        GraphSerializer.write(original, file, "/test/project");

        ProjectGraph loaded = GraphSerializer.read(file);

        assertEquals(original.nodeCount(), loaded.nodeCount());
        assertEquals(original.edgeCount(), loaded.edgeCount());
        assertEquals("Foo", loaded.getNode("class:com.example.Foo").properties().get("name"));
        assertEquals(NodeType.CAMEL_ROUTE, loaded.getNode("route:processOrders").type());
    }

    @Test
    void jsonContainsVersionAndStats() throws IOException {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode("class:A", NodeType.CLASS, Map.of()));
        graph.addNode(new GraphNode("class:B", NodeType.CLASS, Map.of()));
        graph.addEdge(new GraphEdge("class:A", "class:B", EdgeType.EXTENDS, Map.of()));

        Path file = tempDir.resolve("project-graph.json");
        GraphSerializer.write(graph, file, "/my/project");

        String json = Files.readString(file);
        assertTrue(json.contains("\"version\" : \"1.1\"") || json.contains("\"version\":\"1.1\""));
        assertTrue(json.contains("/my/project"));
    }

    @Test
    void edgePropertiesPreserved() throws IOException {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode("route:r1", NodeType.CAMEL_ROUTE, Map.of()));
        graph.addNode(new GraphNode("processor:r1:marshal:0", NodeType.CAMEL_PROCESSOR, Map.of()));
        graph.addEdge(new GraphEdge(
                "route:r1", "processor:r1:marshal:0",
                EdgeType.PROCESSES, Map.of("order", "0")));

        Path file = tempDir.resolve("project-graph.json");
        GraphSerializer.write(graph, file, "/test");

        ProjectGraph loaded = GraphSerializer.read(file);
        GraphEdge edge = loaded.getEdges().get(0);
        assertEquals("0", edge.properties().get("order"));
    }

    @Test
    void traverseAndRewriteWithoutTheSchemaThenRecoverLegacyTypesWithIt() throws IOException {
        Path file = tempDir.resolve("legacy.json");
        Files.writeString(file, """
                {"version":"1.0","nodes":{
                  "flow":{"type":"MULE_FLOW","properties":{"file":"orders.xml","value":"001"}},
                  "sub":{"type":"MULE_SUB_FLOW"},"orch":{"type":"BIZTALK_ORCHESTRATION"},
                  "shape":{"type":"BIZTALK_SHAPE"}},
                 "edges":[{"from":"flow","to":"sub","type":"MULE_CALLS_SUBFLOW","properties":{"order":"02"}},
                          {"from":"orch","to":"shape","type":"BIZTALK_ORCHESTRATION_CONTAINS"}]}
                """);
        var opaque = GraphSerializer.read(file);
        assertEquals("MULE_FLOW", opaque.getNode("flow").type().name());
        assertEquals(List.of(opaque.getNode("sub")),
                new GraphQuery(opaque).neighbors("flow", "out", new EdgeType("MULE_CALLS_SUBFLOW"), 1).nodes());
        Path rewritten = tempDir.resolve("saved.json");
        GraphSerializer.write(opaque, rewritten, "/project");
        var copy = GraphSerializer.read(rewritten);
        assertEquals(opaque.getNodes(), copy.getNodes());
        assertEquals(opaque.getEdges(), copy.getEdges());

        var schemas
                = new GraphTypeRegistry(Map.of("mule", new MuleGraphAdapter(), "biztalk", new BizTalkGraphAdapter()));
        for (Path input : List.of(file, rewritten)) {
            var graph = GraphSerializer.read(input, schemas);
            assertEquals(List.of(graph.getNode("flow")), graph.findByType(new NodeType("mule:flow")));
            assertEquals(List.of(graph.getNode("orch")), graph.findByType(schemas.nodeType("BIZTALK_ORCHESTRATION")));
            assertEquals(List.of(graph.getNode("shape")), new GraphQuery(graph).neighbors("orch", "out",
                    schemas.edgeType("BIZTALK_ORCHESTRATION_CONTAINS"), 1).nodes());
            assertEquals("02", graph.getEdges().getFirst().properties().get("order"));
            assertEquals("001", graph.getNode("flow").properties().get("value"));
            assertEquals("MuleSoft Mule (1 flows, 1 sub-flows)",
                    schemas.requireAdapter("mule").summarize(graph).orElseThrow());
            assertEquals("Microsoft BizTalk (1 orchestrations)",
                    schemas.requireAdapter("biztalk").summarize(graph).orElseThrow());
        }
        GraphSerializer.write(GraphSerializer.read(file, schemas), rewritten, "/project");
        var canonical = GraphSerializer.read(rewritten);
        assertEquals("mule:flow", canonical.getNode("flow").type().name());
        assertEquals("biztalk:orchestration-contains", canonical.getEdges().get(1).type().name());
        GraphSerializer.write(canonical, rewritten, "/project");
        assertEquals(canonical.getNodes(), GraphSerializer.read(rewritten).getNodes());
        assertEquals(canonical.getEdges(), GraphSerializer.read(rewritten).getEdges());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'nodes':{' ':{'type':'CLASS'}},'edges':[]",
            "'nodes':{'n':{'type':'bad type'}},'edges':[]",
            "'nodes':{'n':{'type':'vendor:'}},'edges':[]",
            "'nodes':{'n':{'type':'CLASS','properties':{'name':3}}},'edges':[]",
            "'nodes':{'n':{'type':'CLASS','properties':null}},'edges':[]",
            "'nodes':{'n':{}},'edges':[]",
            "'nodes':{'n':{'type':'CLASS'},'n':{'type':'FIELD'}},'edges':[]",
            "'nodes':{},'edges':[{'from':' ','to':'b','type':'CALLS'}]",
            "'nodes':{},'edges':[{'from':'a','type':'CALLS'}]",
            "'nodes':{},'edges':[{'from':'a','to':'b','type':'a/b'}]",
            "'nodes':{},'edges':[{'from':'a','to':'b','type':'CALLS','properties':{'n':null}}]",
            "'nodes':{},'edges':[]},{'trailing':true"
    })
    void rejectsMalformedRecords(String fields) throws IOException {
        Path file = tempDir.resolve("bad.json");
        Files.writeString(file, ("{'version':'1.1'," + fields + "}").replace('\'', '"'));
        var error = assertThrows(IOException.class, () -> GraphSerializer.read(file));
        assertFalse(error.getMessage().isBlank());
    }

    @Test
    void malformedProviderRecordDoesNotOverwriteTheLastGraph() throws IOException {
        Path file = tempDir.resolve("graph.json");
        var graph = new ProjectGraph();
        GraphSerializer.write(graph, file, "/project");
        byte[] previous = Files.readAllBytes(file);
        graph.addEdge(new GraphEdge("a", " ", new EdgeType("fixture:ref"), Map.of()));
        assertTrue(assertThrows(IOException.class, () -> GraphSerializer.write(graph, file, "/project"))
                .getMessage().contains("edge target"));
        assertArrayEquals(previous, Files.readAllBytes(file));
    }

    @Test
    void schemaRegistrationRejectsAnotherNamespaceAndReservedNames() {
        var adapter = new MuleGraphAdapter();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new GraphTypeRegistry(Map.of("other", adapter)).requireAdapter("other")).getMessage()
                .contains("provider/other"));
        GraphTypeAdapter conflict = new GraphTypeAdapter() {
            public Map<String, NodeType> nodeTypes() {
                return Map.of("CLASS", new NodeType("example:class"));
            }

            public Map<String, EdgeType> edgeTypes() {
                return Map.of();
            }

            public java.util.Optional<String> summarize(ProjectGraph graph) {
                return java.util.Optional.empty();
            }
        };
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new GraphTypeRegistry(Map.of("example", conflict)).requireAdapter("example")).getMessage()
                .contains("CLASS already maps to CLASS from core"));
    }

    @Test
    void rejectsUnsupportedVersion() throws IOException {
        Path file = tempDir.resolve("bad-graph.json");
        Files.writeString(file, """
                {
                  "version": "9.9",
                  "nodes": [],
                  "edges": []
                }
                """);

        IOException error = assertThrows(IOException.class, () -> GraphSerializer.read(file));
        assertTrue(error.getMessage().contains("Unsupported graph format version"));
    }
}
