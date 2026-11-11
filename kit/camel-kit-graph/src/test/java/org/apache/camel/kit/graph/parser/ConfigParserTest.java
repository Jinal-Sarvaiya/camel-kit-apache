package org.apache.camel.kit.graph.parser;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.graph.GraphBuilder;
import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.EdgeType;
import org.apache.camel.kit.graph.model.NodeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ConfigParserTest {

    private ProjectGraph graph;
    private static final Path TEST_PROJECT = Path.of("src/test/resources/testdata");

    @BeforeEach
    void setUp() {
        graph = new ProjectGraph();
        new ConfigParser().parse(TEST_PROJECT, graph);
    }

    @Test
    void parsesCamelProperties() {
        assertTrue(graph.hasNode("config:camel.component.kafka.brokers"));
        assertEquals("localhost:9092",
                graph.getNode("config:camel.component.kafka.brokers").properties().get("value"));
    }

    @Test
    void parsesMultipleCamelProperties() {
        assertTrue(graph.hasNode("config:camel.component.kafka.group-id"));
        assertTrue(graph.hasNode("config:camel.component.jdbc.dataSource"));
    }

    @Test
    void capturesNonCamelProperties() {
        assertTrue(graph.hasNode("config:spring.datasource.url"));
        assertTrue(graph.hasNode("config:order.max-retries"));
    }

    @Test
    void allNodesAreConfigPropertyType() {
        graph.findByType(NodeType.CONFIG_PROPERTY).forEach(node -> assertEquals(NodeType.CONFIG_PROPERTY, node.type()));
    }

    @Test
    void propertiesIncludeFileInfo() {
        String file = graph.getNode("config:camel.component.kafka.brokers")
                .properties().get("file");
        assertTrue(file.endsWith("application.properties"));
    }

    @Test
    void capturesAllProperties() throws Exception {
        ProjectGraph graph = new ProjectGraph();
        new ConfigParser().parse(Path.of("src/test/resources/testdata/di"), graph);

        assertNotNull(graph.getNode("config:camel.component.kafka.brokers"),
                "Should capture camel.* properties");
        assertNotNull(graph.getNode("config:spring.datasource.url"),
                "Should capture spring.* properties");
        assertNotNull(graph.getNode("config:order.max-retries"),
                "Should capture application-specific properties");
        assertNotNull(graph.getNode("config:payment.gateway.url"),
                "Should capture all custom properties");
    }

    @Test
    void flattensApplicationYamlAndProfileVariantsLikeProperties(@TempDir Path root) throws Exception {
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                camel:
                  component:
                    kafka:
                      brokers: localhost:9092
                servers:
                  - a
                  - b
                port: 8080
                """);
        Files.writeString(res.resolve("application-dev.yaml"), "order:\n  retries: 3\n");
        ProjectGraph yamlGraph = new ProjectGraph();

        new ConfigParser().parse(root, yamlGraph);

        assertEquals("localhost:9092",
                yamlGraph.getNode("config:camel.component.kafka.brokers").properties().get("value"));
        assertEquals("b", yamlGraph.getNode("config:servers[1]").properties().get("value"));
        assertEquals("8080", yamlGraph.getNode("config:port").properties().get("value"));
        assertEquals("src/main/resources/application.yml",
                yamlGraph.getNode("config:port").properties().get("file"));
        assertEquals("dev", yamlGraph.getNode("config:order.retries").properties().get("profile"));
    }

    @Test
    void multiDocumentYamlKeepsTheDefaultAndTheProfileSpecificValues(@TempDir Path root) throws Exception {
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                camel:
                  component:
                    kafka:
                      brokers: localhost:9092
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                camel:
                  component:
                    kafka:
                      brokers: prod:9092
                """);
        ProjectGraph yamlGraph = new ProjectGraph();

        new ConfigParser().parse(root, yamlGraph);

        var base = yamlGraph.getNode("config:camel.component.kafka.brokers");
        assertNotNull(base, "the default document must stay reachable by its plain key");
        assertEquals("localhost:9092", base.properties().get("value"));
        assertNull(base.properties().get("profile"));
        var prod = yamlGraph.getNode("config:camel.component.kafka.brokers:profile=prod");
        assertNotNull(prod, "the profile-specific value must be preserved, not merged away");
        assertEquals("prod:9092", prod.properties().get("value"));
        assertEquals("prod", prod.properties().get("profile"));
    }

    @Test
    void flatDottedActivationKeyIsRecognisedAsTheDocumentProfile(@TempDir Path root) throws Exception {
        Path res = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), """
                port: 8080
                ---
                spring.config.activate.on-profile: prod
                port: 9090
                """);
        ProjectGraph yamlGraph = new ProjectGraph();

        new ConfigParser().parse(root, yamlGraph);

        assertEquals("8080", yamlGraph.getNode("config:port").properties().get("value"));
        var prod = yamlGraph.getNode("config:port:profile=prod");
        assertNotNull(prod, "a flat dotted activation key must scope the document too");
        assertEquals("9090", prod.properties().get("value"));
        assertEquals("prod", prod.properties().get("profile"));
    }

    /** Connected consumer: a YAML property referenced by a route placeholder must still configure the endpoint. */
    @Test
    void yamlPropertiesStillConfigureTheRouteEndpointsThatReferenceThem(@TempDir Path root) throws Exception {
        Path res = Files.createDirectories(root.resolve("src/main/resources/camel"));
        Files.writeString(res.getParent().resolve("application.yml"), """
                timer:
                  name: tick
                ---
                spring.config.activate.on-profile: prod
                kafka:
                  topic: prod-topic
                """);
        Files.writeString(res.resolve("routes.yaml"), """
                - route:
                    id: ticker
                    from:
                      uri: "timer:{{timer.name}}"
                      steps:
                        - to:
                            uri: "kafka:{{kafka.topic}}"
                """);

        ProjectGraph built = new GraphBuilder().build(root);

        assertTrue(built.getEdges().stream().anyMatch(e -> e.type() == EdgeType.CONFIGURES
                && e.from().equals("config:timer.name") && e.to().startsWith("endpoint:timer")),
                "a default YAML property must keep its CONFIGURES edge");
        assertTrue(built.getEdges().stream().anyMatch(e -> e.type() == EdgeType.CONFIGURES
                && e.from().equals("config:kafka.topic:profile=prod") && e.to().startsWith("endpoint:kafka")),
                "a property defined only in a profile document must still be linked");
    }
}
