package org.apache.camel.kit.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.model.NodeType;

/**
 * Discovers detailed information about an existing Camel project.
 */
public final class ProjectDiscovery {

    private ProjectDiscovery() {
    }

    /**
     * Discovers comprehensive project details from the graph.
     *
     * @param  graph the project graph
     * @return       discovery result with project metadata
     */
    public static DiscoveryResult discover(ProjectGraph graph) {
        // Detect all runtimes present (for conflict detection)
        Set<String> detectedRuntimes = detectAllRuntimes(graph);
        String runtime = detectedRuntimes.isEmpty() ? "main" : detectedRuntimes.iterator().next();

        // Detect all Camel versions present (for conflict detection)
        Set<String> allCamelVersions = detectAllCamelVersions(graph);
        String camelVersion = allCamelVersions.isEmpty() ? null : allCamelVersions.iterator().next();

        String platformBomVersion = detectPlatformBomVersion(graph, runtime);
        Set<DslType> dslTypes = detectDslTypes(graph);
        List<String> routes = detectRoutes(graph);
        List<String> routeFiles = detectRouteFiles(graph);
        boolean hasTests = detectTests(graph);
        List<String> modules = detectModules(graph);
        int beansCount = detectBeansCount(graph);
        int configFilesCount = detectConfigFilesCount(graph);

        // Build result with conflict information
        DiscoveryResult result = new DiscoveryResult(
                runtime,
                camelVersion,
                platformBomVersion,
                dslTypes,
                routes,
                routeFiles,
                hasTests,
                modules,
                beansCount,
                configFilesCount,
                detectedRuntimes,
                allCamelVersions);

        return result;
    }

    /**
     * Detect all runtimes present in the project (for conflict detection).
     */
    private static Set<String> detectAllRuntimes(ProjectGraph graph) {
        Set<String> runtimes = new HashSet<>();
        for (GraphNode artifact : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            String artifactId = artifact.properties().getOrDefault("artifactId", "");
            if (artifactId.startsWith("camel-spring-boot")) {
                runtimes.add("spring-boot");
            }
            if (artifactId.startsWith("camel-quarkus")) {
                runtimes.add("quarkus");
            }
            if ("camel-blueprint".equals(artifactId)) {
                runtimes.add("karaf");
            }
        }
        return runtimes;
    }

    /**
     * Detect all distinct Camel versions in the project (for conflict detection).
     */
    private static Set<String> detectAllCamelVersions(ProjectGraph graph) {
        Set<String> versions = new HashSet<>();

        // Check for camel.version property
        GraphNode versionProp = graph.getNode("maven-property:camel.version");
        if (versionProp != null) {
            String value = versionProp.properties().get("value");
            if (value != null && !value.isEmpty() && !value.contains("${")) {
                versions.add(value);
            }
        }

        // Check all Camel artifacts
        for (GraphNode node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            if ("org.apache.camel".equals(node.properties().get("groupId"))) {
                String version = node.properties().get("version");
                if (version != null && !"managed".equals(version) && !"unknown".equals(version)
                        && !version.contains("${")) {
                    versions.add(version);
                }
            }
        }

        return versions;
    }

    private static String detectCamelVersion(ProjectGraph graph) {
        // Check for camel.version property (highest priority)
        GraphNode versionProp = graph.getNode("maven-property:camel.version");
        if (versionProp != null) {
            String value = versionProp.properties().get("value");
            if (value != null && !value.isEmpty() && !value.contains("${")) {
                return value;
            }
        }

        // Check camel-core artifact
        GraphNode camelCore = graph.getNode("maven:org.apache.camel:camel-core");
        if (camelCore != null) {
            String version = camelCore.properties().get("version");
            if (version != null && !"managed".equals(version) && !"unknown".equals(version)
                    && !version.contains("${")) {
                return version;
            }
        }

        // Check camel-bom
        GraphNode camelBom = graph.getNode("maven:org.apache.camel:camel-bom");
        if (camelBom != null) {
            String version = camelBom.properties().get("version");
            if (version != null && !"managed".equals(version) && !"unknown".equals(version)
                    && !version.contains("${")) {
                return version;
            }
        }

        // Search all Camel artifacts (fallback)
        for (GraphNode node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            if ("org.apache.camel".equals(node.properties().get("groupId"))) {
                String version = node.properties().get("version");
                if (version != null && !"managed".equals(version) && !"unknown".equals(version)
                        && !version.contains("${")) {
                    return version;
                }
            }
        }

        return null;
    }

    private static String detectPlatformBomVersion(ProjectGraph graph, String runtime) {
        if ("quarkus".equals(runtime)) {
            GraphNode quarkusBom = graph.getNode("maven:io.quarkus.platform:quarkus-bom");
            if (quarkusBom != null) {
                String version = quarkusBom.properties().get("version");
                if (version != null && !"managed".equals(version)) {
                    return version;
                }
            }
        } else if ("spring-boot".equals(runtime)) {
            GraphNode springBom = graph.getNode("maven:org.springframework.boot:spring-boot-dependencies");
            if (springBom != null) {
                String version = springBom.properties().get("version");
                if (version != null && !"managed".equals(version)) {
                    return version;
                }
            }
        }
        return null;
    }

    private static Set<DslType> detectDslTypes(ProjectGraph graph) {
        Set<DslType> dslTypes = new HashSet<>();

        // Check for YAML routes
        for (GraphNode node : graph.findByType(NodeType.CAMEL_ROUTE)) {
            String file = node.properties().get("file");
            if (file != null) {
                if (file.endsWith(".yaml") || file.endsWith(".yml")) {
                    dslTypes.add(DslType.YAML);
                } else if (file.endsWith(".xml")) {
                    dslTypes.add(DslType.XML);
                } else if (file.endsWith(".java")) {
                    dslTypes.add(DslType.JAVA);
                } else if (file.endsWith(".groovy")) {
                    dslTypes.add(DslType.GROOVY);
                }
            }
        }

        return dslTypes;
    }

    /**
     * Route IDs, one per route. A file can define several routes, so this list is what route counts must use.
     */
    private static List<String> detectRoutes(ProjectGraph graph) {
        List<String> routes = new ArrayList<>();
        for (GraphNode node : graph.findByType(NodeType.CAMEL_ROUTE)) {
            String routeId = node.properties().get("routeId");
            if (routeId != null && !routeId.isEmpty()) {
                routes.add(routeId);
            }
        }
        return routes;
    }

    /**
     * Unique, sorted route source files. Used for the baseline inventory so later stages can tell which files existed
     * before a feature was added.
     */
    private static List<String> detectRouteFiles(ProjectGraph graph) {
        Set<String> routeFiles = new HashSet<>();
        for (GraphNode node : graph.findByType(NodeType.CAMEL_ROUTE)) {
            String file = node.properties().get("file");
            if (file != null && !file.isEmpty()) {
                routeFiles.add(file);
            }
        }
        return routeFiles.stream().sorted().toList();
    }

    private static boolean detectTests(ProjectGraph graph) {
        // Look for test-scoped dependencies or test files
        for (GraphNode node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            String scope = node.properties().get("scope");
            if ("test".equals(scope)) {
                return true;
            }
        }

        // Look for Java test files
        for (GraphNode node : graph.findByType(NodeType.CLASS)) {
            String file = node.properties().get("file");
            if (file != null && file.contains("/test/") && !"yaml".equals(node.properties().get("source"))) {
                return true;
            }
        }

        return false;
    }

    public enum DslType {
        YAML,
        XML,
        JAVA,
        GROOVY
    }

    /**
     * @param routes           route IDs (one entry per route)
     * @param routeFiles       unique route source files, sorted
     * @param detectedRuntimes all runtimes found (for conflict detection)
     * @param allCamelVersions all Camel versions found (for conflict detection)
     */
    public record DiscoveryResult(
            String runtime,
            String camelVersion,
            String platformBomVersion,
            Set<DslType> dslTypes,
            List<String> routes,
            List<String> routeFiles,
            boolean hasTests,
            List<String> modules,
            int beansCount,
            int configFilesCount,
            Set<String> detectedRuntimes,
            Set<String> allCamelVersions) {

        public boolean isUnsupportedVersion() {
            if (camelVersion == null) {
                return false;
            }
            // Camel 3.x is unsupported
            return camelVersion.startsWith("3.");
        }

        /**
         * Reasons the detected runtime/DSL combination is outside the supported scope (Camel 4, YAML routes, Camel
         * Main, Spring Boot or Quarkus). Each entry is an actionable sentence. Empty when the combination is supported
         * or when there is nothing detected to judge (that case is reported by {@link #coverageGaps()}).
         */
        public List<String> unsupportedCombinationReasons() {
            List<String> reasons = new ArrayList<>();
            if ("karaf".equals(runtime)) {
                reasons.add("Runtime 'karaf' (camel-blueprint) is not supported. Existing-project features support "
                            + "Camel Main, Spring Boot and Quarkus.");
            }
            if (!dslTypes.isEmpty() && !dslTypes.contains(DslType.YAML)) {
                reasons.add("No YAML routes were found; detected DSL: " + dslTypes.stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(", "))
                            + ". Existing-project features currently support YAML routes only, and the DSL is never "
                            + "converted.");
            }
            return List.copyOf(reasons);
        }

        /**
         * Things the graph could not determine, so the workflow must inspect the source or ask instead of assuming.
         * Each entry is an actionable sentence.
         */
        public List<String> coverageGaps() {
            List<String> gaps = new ArrayList<>();

            // Check for conflicting runtimes
            if (detectedRuntimes != null && detectedRuntimes.size() > 1) {
                gaps.add("Multiple conflicting runtimes detected: "
                         + String.join(", ", detectedRuntimes)
                         + ". A project cannot use multiple runtimes simultaneously. "
                         + "Inspect pom.xml dependencies and resolve the conflict before proceeding.");
            }

            // Check for conflicting Camel versions
            if (allCamelVersions != null && allCamelVersions.size() > 1) {
                gaps.add("Multiple conflicting Camel versions detected: "
                         + String.join(", ", allCamelVersions)
                         + ". Inspect pom.xml dependencies and camel.version property. "
                         + "All org.apache.camel artifacts must use the same version.");
            }

            if (routes.isEmpty()) {
                gaps.add("No routes were found in the project graph. Inspect the existing route sources before "
                         + "changing them.");
            }
            if (dslTypes.contains(DslType.YAML) && dslTypes.size() > 1) {
                gaps.add("Routes in more than one DSL were found (" + dslTypes.stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(", "))
                         + "). New routes use YAML; do not convert the others.");
            }
            return List.copyOf(gaps);
        }

        public String primaryDsl() {
            if (dslTypes.isEmpty()) {
                return "unknown";
            }
            // Prefer YAML > XML > Java > Groovy
            if (dslTypes.contains(DslType.YAML)) {
                return "yaml";
            }
            if (dslTypes.contains(DslType.XML)) {
                return "xml";
            }
            if (dslTypes.contains(DslType.JAVA)) {
                return "java";
            }
            return "groovy";
        }
    }

    /**
     * Maven modules below the project root: the directory of every parsed {@code pom.xml} other than the root one.
     * {@code PomParser} records the pom path on the project's {@code MAVEN_ARTIFACT} node. Empty for a single-module
     * project.
     */
    private static List<String> detectModules(ProjectGraph graph) {
        Set<String> modules = new HashSet<>();
        for (GraphNode node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            String file = node.properties().get("file");
            if (file == null) {
                continue;
            }
            String normalized = file.replace('\\', '/');
            int lastSlash = normalized.lastIndexOf('/');
            if (normalized.endsWith("pom.xml") && lastSlash > 0) {
                modules.add(normalized.substring(0, lastSlash));
            }
        }
        return modules.stream().sorted().toList();
    }

    /**
     * Number of classes the Java parser flagged as beans ({@code bean=true}, set for Spring, CDI and similar
     * annotations). Classes registered another way, such as in a {@code @Configuration} {@code @Bean} method or YAML
     * beans, are not counted.
     */
    private static int detectBeansCount(ProjectGraph graph) {
        int count = 0;
        for (GraphNode node : graph.findByType(NodeType.CLASS)) {
            if ("true".equals(node.properties().get("bean"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Number of distinct application configuration files ({@code application*.properties}) the graph read. Maven
     * {@code <properties>} also appear as {@code CONFIG_PROPERTY} nodes, so pom files are excluded. Properties files
     * are the only configuration format the graph parses today; {@code application.yml} is not counted.
     */
    private static int detectConfigFilesCount(ProjectGraph graph) {
        Set<String> files = new HashSet<>();
        for (GraphNode node : graph.findByType(NodeType.CONFIG_PROPERTY)) {
            String file = node.properties().get("file");
            if (file != null && !file.endsWith("pom.xml")) {
                files.add(file.replace('\\', '/'));
            }
        }
        return files.size();
    }
}
