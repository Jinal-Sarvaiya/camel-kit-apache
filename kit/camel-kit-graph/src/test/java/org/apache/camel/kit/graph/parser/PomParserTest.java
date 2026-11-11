package org.apache.camel.kit.graph.parser;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.EdgeType;
import org.apache.camel.kit.graph.model.NodeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PomParserTest {

    private ProjectGraph graph;
    private static final Path TEST_PROJECT = Path.of("src/test/resources/testdata");

    @BeforeEach
    void setUp() {
        graph = new ProjectGraph();
        new PomParser().parse(TEST_PROJECT, graph);
    }

    @Test
    void parsesProjectArtifact() {
        assertTrue(graph.hasNode("maven:com.example:order-service"));
        assertEquals("1.0.0",
                graph.getNode("maven:com.example:order-service").properties().get("version"));
    }

    @Test
    void parsesDependencies() {
        assertTrue(graph.hasNode("maven:org.apache.camel:camel-core"));
        assertTrue(graph.hasNode("maven:org.apache.camel:camel-kafka"));
        assertTrue(graph.hasNode("maven:org.apache.camel:camel-jdbc"));
    }

    @Test
    void createsDependsOnEdges() {
        long depEdges = graph.getEdges().stream()
                .filter(e -> e.type() == EdgeType.DEPENDS_ON)
                .filter(e -> e.from().equals("maven:com.example:order-service"))
                .count();
        assertEquals(3, depEdges);
    }

    @Test
    void allNodesAreMavenArtifactType() {
        graph.findByType(NodeType.MAVEN_ARTIFACT).forEach(node -> assertEquals(NodeType.MAVEN_ARTIFACT, node.type()));
        assertTrue(graph.findByType(NodeType.MAVEN_ARTIFACT).size() >= 4,
                "Should have at least 4 MAVEN_ARTIFACT nodes (project + 3 deps from root pom)");
    }

    @Test
    void recordsTheParentAndImportedBomsSoDiscoveryCanResolveTheirVersions(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.5</version>
                  </parent>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties><camel.version>4.8.0</camel.version></properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.apache.camel.springboot</groupId>
                        <artifactId>camel-spring-boot-bom</artifactId>
                        <version>${camel.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        ProjectGraph local = new ProjectGraph();

        new PomParser().parse(root, local);

        var parent = local.getNode("maven-parent:org.springframework.boot:spring-boot-starter-parent");
        assertNotNull(parent);
        assertEquals("parent", parent.properties().get("kind"));
        assertEquals("3.3.5", parent.properties().get("version"));
        var bom = local.getNode("maven-managed:org.apache.camel.springboot:camel-spring-boot-bom");
        assertNotNull(bom);
        assertEquals("managed", bom.properties().get("kind"));
        assertEquals("${camel.version}", bom.properties().get("version"));
        assertEquals("pom", bom.properties().get("type"));
        assertEquals("import", bom.properties().get("scope"));
    }
}
