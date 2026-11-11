package org.apache.camel.kit.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.model.NodeType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProjectDiscovery - detecting Camel version, DSL, routes, tests from project graph.
 */
class ProjectDiscoveryTest {

    @Test
    void detectsCamelVersionFromProperty() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven-property:camel.version",
                NodeType.CONFIG_PROPERTY,
                Map.of("value", "4.8.0")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals("4.8.0", result.camelVersion());
    }

    @Test
    void detectsCamelVersionFromCoreArtifact() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven:org.apache.camel:camel-core",
                NodeType.MAVEN_ARTIFACT,
                Map.of(
                        "groupId", "org.apache.camel",
                        "artifactId", "camel-core",
                        "version", "4.8.0")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals("4.8.0", result.camelVersion());
    }

    @Test
    void detectsSpringBootRuntime() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven:org.apache.camel.springboot:camel-spring-boot-starter",
                NodeType.MAVEN_ARTIFACT,
                Map.of(
                        "groupId", "org.apache.camel.springboot",
                        "artifactId", "camel-spring-boot-starter",
                        "version", "4.8.0")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals("spring-boot", result.runtime());
    }

    @Test
    void detectsQuarkusRuntime() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven:org.apache.camel.quarkus:camel-quarkus-core",
                NodeType.MAVEN_ARTIFACT,
                Map.of(
                        "groupId", "org.apache.camel.quarkus",
                        "artifactId", "camel-quarkus-core",
                        "version", "3.15.0")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals("quarkus", result.runtime());
    }

    @Test
    void detectsYamlDslFromRouteFiles() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "route:get-users-api",
                NodeType.CAMEL_ROUTE,
                Map.of(
                        "routeId", "get-users-api",
                        "file", "src/main/resources/camel/api-routes.yaml")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertTrue(result.dslTypes().contains(ProjectDiscovery.DslType.YAML));
        assertEquals("yaml", result.primaryDsl());
    }

    @Test
    void detectsXmlDslFromRouteFiles() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "route:order-processing",
                NodeType.CAMEL_ROUTE,
                Map.of(
                        "routeId", "order-processing",
                        "file", "src/main/resources/camel/routes.xml")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertTrue(result.dslTypes().contains(ProjectDiscovery.DslType.XML));
        assertEquals("xml", result.primaryDsl());
    }

    @Test
    void detectsJavaDslFromRouteFiles() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "route:my-route",
                NodeType.CAMEL_ROUTE,
                Map.of(
                        "routeId", "my-route",
                        "file", "src/main/java/com/example/MyRoute.java")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertTrue(result.dslTypes().contains(ProjectDiscovery.DslType.JAVA));
        assertEquals("java", result.primaryDsl());
    }

    @Test
    void countsRoutes() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode("route:1", NodeType.CAMEL_ROUTE, Map.of("routeId", "route-1")));
        graph.addNode(new GraphNode("route:2", NodeType.CAMEL_ROUTE, Map.of("routeId", "route-2")));
        graph.addNode(new GraphNode("route:3", NodeType.CAMEL_ROUTE, Map.of("routeId", "route-3")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals(3, result.routes().size());
        assertTrue(result.routes().contains("route-1"));
        assertTrue(result.routes().contains("route-2"));
        assertTrue(result.routes().contains("route-3"));
    }

    @Test
    void countsEveryRouteEvenWhenSeveralShareAFile() {
        ProjectGraph graph = new ProjectGraph();
        String sharedFile = "src/main/resources/camel/api-routes.yaml";
        graph.addNode(new GraphNode(
                "route:a", NodeType.CAMEL_ROUTE,
                Map.of("routeId", "get-users-api", "file", sharedFile)));
        graph.addNode(new GraphNode(
                "route:b", NodeType.CAMEL_ROUTE,
                Map.of("routeId", "health-check", "file", sharedFile)));
        graph.addNode(new GraphNode(
                "route:c", NodeType.CAMEL_ROUTE,
                Map.of("routeId", "scheduled-task", "file", "src/main/resources/camel/timer-routes.yaml")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals(3, result.routes().size(), "route count is per route, not per file");
        assertEquals(
                List.of("src/main/resources/camel/api-routes.yaml", "src/main/resources/camel/timer-routes.yaml"),
                result.routeFiles(),
                "baseline inventory lists each route file once, sorted");
    }

    @Test
    void routeFilesIgnoreRoutesWithoutASourceFile() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode("route:1", NodeType.CAMEL_ROUTE, Map.of("routeId", "route-1")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals(1, result.routes().size());
        assertTrue(result.routeFiles().isEmpty());
    }

    @Test
    void detectsTestsFromTestScopeDependencies() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven:org.apache.camel:camel-test-spring-junit5",
                NodeType.MAVEN_ARTIFACT,
                Map.of(
                        "groupId", "org.apache.camel",
                        "artifactId", "camel-test-spring-junit5",
                        "scope", "test")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertTrue(result.hasTests());
    }

    @Test
    void detectsTestsFromTestFiles() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "class:MyTest",
                NodeType.CLASS,
                Map.of(
                        "className", "com.example.MyTest",
                        "file", "src/test/java/com/example/MyTest.java")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertTrue(result.hasTests());
    }

    @Test
    void flagsCamel3AsUnsupported() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven-property:camel.version",
                NodeType.CONFIG_PROPERTY,
                Map.of("value", "3.21.0")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals("3.21.0", result.camelVersion());
        assertTrue(result.isUnsupportedVersion());
    }

    @Test
    void camel4IsSupported() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven-property:camel.version",
                NodeType.CONFIG_PROPERTY,
                Map.of("value", "4.8.0")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals("4.8.0", result.camelVersion());
        assertFalse(result.isUnsupportedVersion());
    }

    @Test
    void prefersYamlOverXmlForPrimaryDsl() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "route:yaml-route",
                NodeType.CAMEL_ROUTE,
                Map.of("file", "routes.yaml")));
        graph.addNode(new GraphNode(
                "route:xml-route",
                NodeType.CAMEL_ROUTE,
                Map.of("file", "routes.xml")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals(2, result.dslTypes().size());
        assertEquals("yaml", result.primaryDsl(), "YAML should be preferred over XML");
    }

    @Test
    void returnsMainRuntimeWhenNoCamelArtifactsFound() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven:com.example:my-lib",
                NodeType.MAVEN_ARTIFACT,
                Map.of("groupId", "com.example", "artifactId", "my-lib")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals("main", result.runtime());
    }

    @Test
    void returnsNullVersionWhenNoCamelVersionFound() {
        ProjectGraph graph = new ProjectGraph();

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertNull(result.camelVersion());
        assertFalse(result.isUnsupportedVersion());
    }

    @Test
    void moduleDetectionUsesPomFilesOfNestedModulesOnly() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode("maven:g:root", NodeType.MAVEN_ARTIFACT, Map.of("file", "pom.xml")));
        graph.addNode(new GraphNode("maven:g:a", NodeType.MAVEN_ARTIFACT, Map.of("file", "svc/a/pom.xml")));
        graph.addNode(new GraphNode("maven:g:b", NodeType.MAVEN_ARTIFACT, Map.of("file", "svc/b/pom.xml")));
        graph.addNode(new GraphNode("maven:g:c", NodeType.MAVEN_ARTIFACT, Map.of("file", "core/pom.xml")));
        // dependency nodes carry no "file" and must not count as modules
        graph.addNode(new GraphNode(
                "maven:org.apache.camel:camel-core", NodeType.MAVEN_ARTIFACT,
                Map.of("groupId", "org.apache.camel", "artifactId", "camel-core")));

        assertEquals(List.of("core", "svc/a", "svc/b"), ProjectDiscovery.discover(graph).modules());
    }

    @Test
    void singleModuleProjectHasNoModules() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode("maven:g:root", NodeType.MAVEN_ARTIFACT, Map.of("file", "pom.xml")));

        assertTrue(ProjectDiscovery.discover(graph).modules().isEmpty());
    }

    @Test
    void beansAreCountedFromTheParsersBeanFlagNotFromClassNames() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode("class:x.Helper", NodeType.CLASS, Map.of("name", "Helper", "bean", "true")));
        graph.addNode(new GraphNode("class:x.Audit", NodeType.CLASS, Map.of("name", "Audit", "bean", "true")));
        // looks like a bean by name and path, but is not annotated
        graph.addNode(new GraphNode(
                "class:x.OrderService", NodeType.CLASS,
                Map.of("name", "OrderService", "file", "src/main/java/x/service/OrderService.java")));

        assertEquals(2, ProjectDiscovery.discover(graph).beansCount());
    }

    @Test
    void configFilesAreCountedOncePerFileAndPomPropertiesAreExcluded() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "config:a", NodeType.CONFIG_PROPERTY,
                Map.of("key", "a", "file", "src/main/resources/application.properties")));
        graph.addNode(new GraphNode(
                "config:b", NodeType.CONFIG_PROPERTY,
                Map.of("key", "b", "file", "src/main/resources/application.properties")));
        graph.addNode(new GraphNode(
                "config:c", NodeType.CONFIG_PROPERTY,
                Map.of("key", "c", "file", "src/main/resources/application-dev.properties", "profile", "dev")));
        graph.addNode(new GraphNode(
                "maven-property:camel.version", NodeType.CONFIG_PROPERTY,
                Map.of("name", "camel.version", "value", "4.8.0", "file", "pom.xml")));

        assertEquals(2, ProjectDiscovery.discover(graph).configFilesCount());
    }

    @Test
    void discoversModulesBeansAndConfigFromRealProjectFiles(@TempDir Path root) throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>%s</artifactId><version>1</version>
                  %s
                </project>
                """;
        Files.writeString(root.resolve("pom.xml"),
                String.format(java.util.Locale.ROOT, pom, "root", "<packaging>pom</packaging>"));
        Path module = Files.createDirectories(root.resolve("svc/a"));
        Files.writeString(module.resolve("pom.xml"), String.format(java.util.Locale.ROOT, pom, "a", """
                <dependencies><dependency>
                  <groupId>org.apache.camel</groupId><artifactId>camel-core</artifactId><version>4.8.0</version>
                </dependency></dependencies>"""));
        Path java = Files.createDirectories(module.resolve("src/main/java/x"));
        Files.writeString(java.resolve("Helper.java"),
                "package x;\nimport org.springframework.stereotype.Component;\n@Component\npublic class Helper {}\n");
        Files.writeString(java.resolve("OrderService.java"), "package x;\npublic class OrderService {}\n");
        Path resources = Files.createDirectories(module.resolve("src/main/resources"));
        Files.writeString(resources.resolve("application.properties"), "a=1\nb=2\nc=3\n");

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(new GraphBuilder().build(root));

        assertEquals(List.of("svc/a"), result.modules());
        assertEquals(1, result.beansCount(), "only the annotated class is a bean");
        assertEquals(1, result.configFilesCount(), "three properties in one file is one configuration file");
    }

    @Test
    void karafRuntimeAndNonYamlDslAreUnsupportedCombinations() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "maven:org.apache.camel:camel-blueprint", NodeType.MAVEN_ARTIFACT,
                Map.of("groupId", "org.apache.camel", "artifactId", "camel-blueprint", "version", "4.8.0")));
        graph.addNode(new GraphNode(
                "route:r", NodeType.CAMEL_ROUTE, Map.of("routeId", "r", "file", "src/main/java/R.java")));

        List<String> reasons = ProjectDiscovery.discover(graph).unsupportedCombinationReasons();

        assertEquals(2, reasons.size());
        assertTrue(reasons.get(0).contains("karaf"));
        assertTrue(reasons.get(1).contains("No YAML routes were found"));
    }

    @Test
    void yamlRoutesOnSupportedRuntimeHaveNoUnsupportedReasonsOrGaps() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "route:r", NodeType.CAMEL_ROUTE, Map.of("routeId", "r", "file", "src/main/resources/camel/r.yaml")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertTrue(result.unsupportedCombinationReasons().isEmpty());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test
    void emptyGraphAndMixedDslProduceCoverageGaps() {
        assertEquals(1, ProjectDiscovery.discover(new ProjectGraph()).coverageGaps().size());

        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "route:y", NodeType.CAMEL_ROUTE, Map.of("routeId", "y", "file", "a.yaml")));
        graph.addNode(new GraphNode(
                "route:j", NodeType.CAMEL_ROUTE, Map.of("routeId", "j", "file", "A.java")));

        List<String> gaps = ProjectDiscovery.discover(graph).coverageGaps();
        assertEquals(1, gaps.size());
        assertTrue(gaps.get(0).contains("more than one DSL"));
        assertTrue(ProjectDiscovery.discover(graph).unsupportedCombinationReasons().isEmpty(),
                "mixed DSL with YAML present is supported; the others are simply not converted");
    }

    @Test
    void yamlBeansAreCountedAndYamlConfigIsCountedFromRealFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                </project>
                """);
        Path resources = Files.createDirectories(root.resolve("src/main/resources/camel"));
        Files.writeString(resources.resolve("routes.yaml"), """
                - beans:
                    - name: helper
                      type: x.Helper
                - route:
                    id: r1
                    from:
                      uri: timer:t
                      steps:
                        - log: hi
                """);
        Files.writeString(root.resolve("src/main/resources/application.yml"), "a:\n  b: 1\n");

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(new GraphBuilder().build(root));

        assertEquals(1, result.beansCount(), "YAML beans: entries are beans");
        assertEquals(List.of("r1"), result.routes());
        assertEquals(1, result.configFilesCount(), "application.yml is a configuration file");
    }

    @Test
    void emptyGraphYieldsNoRoutesNoFilesAndNoTests() {
        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(new ProjectGraph());

        assertTrue(result.routes().isEmpty());
        assertTrue(result.routeFiles().isEmpty());
        assertTrue(result.dslTypes().isEmpty());
        assertFalse(result.hasTests());
    }

    @Test
    void mixedYamlAndXmlRoutesRecordBothDslTypes() {
        ProjectGraph graph = new ProjectGraph();
        graph.addNode(new GraphNode(
                "route:y", NodeType.CAMEL_ROUTE, Map.of("routeId", "y", "file", "routes.yaml")));
        graph.addNode(new GraphNode(
                "route:x", NodeType.CAMEL_ROUTE, Map.of("routeId", "x", "file", "routes.xml")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        assertEquals(
                java.util.Set.of(ProjectDiscovery.DslType.YAML, ProjectDiscovery.DslType.XML),
                new java.util.HashSet<>(result.dslTypes()));
        assertEquals(2, result.routes().size());
        assertEquals(List.of("routes.xml", "routes.yaml"), result.routeFiles());
    }

    @Test
    void discoversCompleteProjectMetadata() {
        // Simulate a complete existing Spring Boot project
        ProjectGraph graph = new ProjectGraph();

        // Camel version
        graph.addNode(new GraphNode(
                "maven-property:camel.version",
                NodeType.CONFIG_PROPERTY,
                Map.of("value", "4.8.0")));

        // Spring Boot runtime
        graph.addNode(new GraphNode(
                "maven:org.apache.camel.springboot:camel-spring-boot-starter",
                NodeType.MAVEN_ARTIFACT,
                Map.of("artifactId", "camel-spring-boot-starter")));

        // YAML routes
        graph.addNode(new GraphNode(
                "route:api-route",
                NodeType.CAMEL_ROUTE,
                Map.of("routeId", "get-users", "file", "src/main/resources/camel/api.yaml")));
        graph.addNode(new GraphNode(
                "route:timer-route",
                NodeType.CAMEL_ROUTE,
                Map.of("routeId", "scheduled-task", "file", "src/main/resources/camel/timer.yaml")));

        // Tests
        graph.addNode(new GraphNode(
                "maven:org.springframework.boot:spring-boot-starter-test",
                NodeType.MAVEN_ARTIFACT,
                Map.of("scope", "test")));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discover(graph);

        // Verify all metadata
        assertEquals("4.8.0", result.camelVersion());
        assertEquals("spring-boot", result.runtime());
        assertEquals("yaml", result.primaryDsl());
        assertEquals(2, result.routes().size());
        assertTrue(result.hasTests());
        assertFalse(result.isUnsupportedVersion());
    }
}
