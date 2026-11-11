package org.apache.camel.kit.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
        // Runtime: every candidate with its evidence. More than one candidate is a conflict, never a silent pick.
        Map<String, Set<String>> runtimeEvidence = detectRuntimeEvidence(graph);
        Set<String> detectedRuntimes = new TreeSet<>(runtimeEvidence.keySet());
        String runtime = detectedRuntimes.isEmpty() ? "main"
                : detectedRuntimes.size() == 1 ? detectedRuntimes.iterator().next() : null;

        // Camel version: every candidate with its source. Several different versions is a conflict.
        Map<String, Set<String>> versionEvidence = detectCamelVersionEvidence(graph);
        Set<String> allCamelVersions = new TreeSet<>(versionEvidence.keySet());
        String camelVersion = allCamelVersions.size() == 1 ? allCamelVersions.iterator().next() : null;

        String platformBomVersion = runtime == null ? null : detectPlatformBomVersion(graph, runtime);
        String springBootVersion = "spring-boot".equals(runtime) ? detectSpringBootVersion(graph) : null;
        Set<DslType> dslTypes = detectDslTypes(graph);
        List<String> routes = detectRoutes(graph);
        List<String> routeFiles = detectRouteFiles(graph);
        boolean hasTests = detectTests(graph);
        List<String> modules = detectModules(graph);
        int beansCount = detectBeansCount(graph);
        int configFilesCount = detectConfigFilesCount(graph);

        return new DiscoveryResult(
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
                allCamelVersions,
                springBootVersion,
                immutable(runtimeEvidence),
                immutable(versionEvidence));
    }

    private static Map<String, Set<String>> immutable(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new TreeMap<>();
        source.forEach((key, value) -> copy.put(key, Collections.unmodifiableSet(new TreeSet<>(value))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Every runtime the project's artifacts point at, with the artifacts that are the evidence. Camel Main is the
     * absence of any of these.
     */
    private static Map<String, Set<String>> detectRuntimeEvidence(ProjectGraph graph) {
        Map<String, Set<String>> runtimes = new TreeMap<>();
        for (GraphNode artifact : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            String artifactId = artifact.properties().getOrDefault("artifactId", "");
            String module = artifact.properties().getOrDefault("file", "dependency");
            if (artifactId.startsWith("camel-spring-boot")) {
                runtimes.computeIfAbsent("spring-boot", k -> new TreeSet<>()).add(artifactId + " (" + module + ")");
            }
            if (artifactId.startsWith("camel-quarkus")) {
                runtimes.computeIfAbsent("quarkus", k -> new TreeSet<>()).add(artifactId + " (" + module + ")");
            }
            if ("camel-blueprint".equals(artifactId)) {
                runtimes.computeIfAbsent("karaf", k -> new TreeSet<>()).add(artifactId + " (" + module + ")");
            }
        }
        return runtimes;
    }

    /**
     * Every distinct Camel version the project declares, mapped to where it was declared: the {@code camel.version}
     * property, explicit {@code org.apache.camel} dependency versions, and an imported Camel BOM. A version that is
     * only a placeholder ({@code ${...}}) or inherited ({@code managed}) is not evidence.
     */
    private static Map<String, Set<String>> detectCamelVersionEvidence(ProjectGraph graph) {
        Map<String, Set<String>> versions = new TreeMap<>();

        GraphNode versionProp = graph.getNode("maven-property:camel.version");
        if (versionProp != null) {
            addVersion(versions, versionProp.properties().get("value"), "property camel.version");
        }
        for (GraphNode node : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            if ("org.apache.camel".equals(node.properties().get("groupId"))) {
                addVersion(versions, node.properties().get("version"),
                        "dependency " + node.properties().get("artifactId"));
            }
        }
        for (GraphNode managed : graph.findByType(NodeType.CONFIG_PROPERTY)) {
            if ("managed".equals(managed.properties().get("kind"))
                    && "pom".equals(managed.properties().get("type"))
                    && isCamelBom(managed.properties().get("groupId"), managed.properties().get("artifactId"))) {
                addVersion(versions, resolve(graph, managed.properties().get("version")),
                        "imported BOM " + managed.properties().get("artifactId"));
            }
        }
        return versions;
    }

    private static boolean isCamelBom(String groupId, String artifactId) {
        return artifactId != null && artifactId.startsWith("camel-") && artifactId.endsWith("-bom")
                && groupId != null && groupId.startsWith("org.apache.camel");
    }

    private static void addVersion(Map<String, Set<String>> versions, String version, String source) {
        if (version != null && !version.isBlank() && !"managed".equals(version) && !"unknown".equals(version)
                && !version.contains("${")) {
            versions.computeIfAbsent(version, k -> new TreeSet<>()).add(source);
        }
    }

    /**
     * Resolves a {@code ${property}} reference against the Maven properties the parser recorded; a value that is not a
     * reference, or whose property is unknown, is returned unchanged.
     */
    private static String resolve(ProjectGraph graph, String value) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        GraphNode property = graph.getNode("maven-property:" + value.substring(2, value.length() - 1));
        return property == null ? value : property.properties().getOrDefault("value", value);
    }

    /**
     * The platform BOM version declared by the project, or {@code null} when it cannot be established. Spring Boot: the
     * imported {@code camel-spring-boot-bom} (the Camel BOM; the framework version is separate, see
     * {@link #detectSpringBootVersion}). Quarkus: the imported or dependency {@code quarkus-bom} / Quarkus platform
     * BOM. Camel Main has no platform BOM.
     */
    private static String detectPlatformBomVersion(ProjectGraph graph, String runtime) {
        if ("quarkus".equals(runtime)) {
            String fromBom = firstManaged(graph, "quarkus-bom", "quarkus-camel-bom");
            if (fromBom != null) {
                return fromBom;
            }
            GraphNode quarkusBom = graph.getNode("maven:io.quarkus.platform:quarkus-bom");
            if (quarkusBom != null) {
                String version = quarkusBom.properties().get("version");
                if (version != null && !"managed".equals(version) && !version.contains("${")) {
                    return version;
                }
            }
        } else if ("spring-boot".equals(runtime)) {
            return firstManaged(graph, "camel-spring-boot-bom");
        }
        return null;
    }

    /**
     * The Spring Boot framework version: an imported {@code spring-boot-dependencies}, otherwise the
     * {@code spring-boot-starter-parent} parent. This is a different artifact from the Camel BOM and is kept apart so
     * the two are never mixed up.
     */
    private static String detectSpringBootVersion(ProjectGraph graph) {
        String fromBom = firstManaged(graph, "spring-boot-dependencies");
        if (fromBom != null) {
            return fromBom;
        }
        for (GraphNode parent : graph.findByType(NodeType.CONFIG_PROPERTY)) {
            if ("parent".equals(parent.properties().get("kind"))
                    && "spring-boot-starter-parent".equals(parent.properties().get("artifactId"))) {
                String version = resolve(graph, parent.properties().get("version"));
                if (version != null && !version.contains("${") && !"null".equals(version)) {
                    return version;
                }
            }
        }
        return null;
    }

    private static String firstManaged(ProjectGraph graph, String... artifactIds) {
        List<String> wanted = List.of(artifactIds);
        for (GraphNode managed : graph.findByType(NodeType.CONFIG_PROPERTY)) {
            if ("managed".equals(managed.properties().get("kind"))
                    && wanted.contains(managed.properties().get("artifactId"))) {
                String version = resolve(graph, managed.properties().get("version"));
                if (version != null && !"managed".equals(version) && !version.contains("${")) {
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
     * @param runtime            the single detected runtime, or {@code null} when conflicting runtimes were found
     * @param camelVersion       the single detected Camel version, or {@code null} when none or several were found
     * @param platformBomVersion the declared platform BOM version, or {@code null} when it could not be established
     * @param routes             route IDs (one entry per route)
     * @param routeFiles         unique route source files, sorted
     * @param detectedRuntimes   every runtime found
     * @param allCamelVersions   every Camel version found
     * @param springBootVersion  the Spring Boot framework version (Spring Boot runtime only), or {@code null}
     * @param runtimeEvidence    for every detected runtime, the artifacts that point at it
     * @param versionEvidence    for every detected Camel version, where it was declared
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
            Set<String> allCamelVersions,
            String springBootVersion,
            Map<String, Set<String>> runtimeEvidence,
            Map<String, Set<String>> versionEvidence) {

        public boolean isUnsupportedVersion() {
            if (camelVersion == null) {
                return false;
            }
            // Camel 3.x is unsupported
            return camelVersion.startsWith("3.");
        }

        /** True when more than one runtime or more than one Camel version was found: nothing may be locked. */
        public boolean hasConflict() {
            return detectedRuntimes.size() > 1 || allCamelVersions.size() > 1;
        }

        /**
         * Reasons the detected runtime/DSL combination is outside the supported scope (Camel 4, YAML routes, Camel
         * Main, Spring Boot or Quarkus). Each entry is an actionable sentence. Empty when the combination is supported
         * or when there is nothing detected to judge (that case is reported by {@link #coverageGaps()}).
         */
        public List<String> unsupportedCombinationReasons() {
            List<String> reasons = new ArrayList<>();
            if (detectedRuntimes.contains("karaf")) {
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

            // Conflicting evidence is never resolved by picking one: the constraint stays unlocked until the user
            // resolves it.
            if (detectedRuntimes.size() > 1) {
                gaps.add("Conflicting runtimes found: " + describe(runtimeEvidence)
                         + ". The runtime is not locked. Resolve which runtime this project uses before planning.");
            }
            if (allCamelVersions.size() > 1) {
                gaps.add("Conflicting Camel versions found: " + describe(versionEvidence)
                         + ". The Camel version is not locked. Align the declarations before planning.");
            }
            if (platformBomVersion == null && ("spring-boot".equals(runtime) || "quarkus".equals(runtime))) {
                gaps.add("The " + runtime + " platform BOM version could not be established from the pom (no imported "
                         + (runtime.equals("spring-boot") ? "camel-spring-boot-bom" : "quarkus-bom")
                         + " with a resolvable version). Inspect the pom and confirm the BOM version before planning.");
            }
            if ("spring-boot".equals(runtime) && springBootVersion == null) {
                gaps.add("The Spring Boot framework version could not be established (no spring-boot-dependencies "
                         + "import or spring-boot-starter-parent). Inspect the pom before planning.");
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

        private static String describe(Map<String, Set<String>> evidence) {
            return evidence.entrySet().stream()
                    .map(e -> e.getKey() + " [" + String.join("; ", e.getValue()) + "]")
                    .collect(java.util.stream.Collectors.joining(", "));
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
