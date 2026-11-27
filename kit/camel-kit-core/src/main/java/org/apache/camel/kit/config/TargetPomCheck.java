package org.apache.camel.kit.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Checks Maven's effective dependency management without executing the generated project. */
public final class TargetPomCheck {
    public enum Application {
        GENERATED,
        EXISTING;

        // A generated POM must supply every declaration. Existing projects may obtain them elsewhere.
        private boolean requires(Object declaration) {
            return this == GENERATED || declaration != null;
        }
    }

    private final Model model;
    private final Map<String, String> managed = new HashMap<>();
    private final String treeCore;

    public TargetPomCheck(byte[] effectivePom, byte[] dependencyTree) throws IOException {
        try {
            model = new MavenXpp3Reader().read(new ByteArrayInputStream(effectivePom));
        } catch (XmlPullParserException e) {
            throw new IOException("Expected a single module's effective POM", e);
        }
        if (model.getDependencyManagement() == null) {
            throw new IOException("Expected a Maven destination with dependency management");
        }
        for (Dependency dep : model.getDependencyManagement().getDependencies()) {
            managed.put(dep.getManagementKey(), dep.getVersion());
        }
        treeCore = dependencyTree == null ? null : resolvedCore(model, dependencyTree);
        if (dependencyTree != null && treeCore == null) {
            throw new IOException(
                    "No runtime org.apache.camel:camel-core-engine in the supplied Maven dependency tree");
        }
    }

    public void completeExistingProfile(Properties config) throws IOException {
        if ("true".equals(config.getProperty("project.needsResolvedCore"))) {
            String core = treeCore != null ? treeCore : managed.get("org.apache.camel:camel-core-engine:jar");
            if (core == null) {
                throw new IOException("Camel core is transitive. Supply --dependency-tree from the same Maven module");
            }
            config.putIfAbsent("project.camelVersion", core);
        }
        if ("quarkus".equals(config.getProperty("project.runtime"))) {
            String catalog = managed.get("org.apache.camel.quarkus:camel-quarkus-catalog:jar");
            if (catalog != null) {
                config.putIfAbsent("project.catalogVersion", catalog);
            }
        } else if ("spring-boot".equals(config.getProperty("project.runtime"))) {
            String boot = managed.get("org.springframework.boot:spring-boot:jar");
            if (boot != null) {
                config.putIfAbsent("project.springBootVersion", boot);
            }
        }
    }

    public void check(TargetRuntimeProfile target, Application application) throws IOException {
        if (target.applicationBom() == null) {
            throw new IOException("Expected a Maven destination with an application BOM");
        }
        if (treeCore != null) {
            requireVersion("resolved org.apache.camel:camel-core-engine", target.camelVersion(), treeCore);
        }
        if (application == Application.GENERATED || treeCore == null) {
            String core = managed.get("org.apache.camel:camel-core-engine:jar");
            if (application == Application.EXISTING && core == null) {
                throw new IOException("Camel core is transitive. Supply --dependency-tree from the same Maven module");
            }
            requireVersion("org.apache.camel:camel-core-engine", target.camelVersion(), core);
        }
        String plugin;
        String pluginVersion;
        if ("spring-boot".equals(target.runtime())) {
            plugin = "org.springframework.boot:spring-boot-maven-plugin";
            pluginVersion = target.springBootVersion();
            requireVersion("Spring Boot framework", target.springBootVersion(),
                    managed.get("org.springframework.boot:spring-boot:jar"));
            requireVersion("Camel Spring Boot starter", target.runtimeCatalogVersion(),
                    managed.get("org.apache.camel.springboot:camel-spring-boot-starter:jar"));
        } else {
            String[] bom = target.applicationBom().split(":");
            plugin = bom[0] + ":quarkus-maven-plugin";
            pluginVersion = bom[2];
            requireVersion("Camel Quarkus catalog", target.requiredCatalogVersion(),
                    managed.get("org.apache.camel.quarkus:camel-quarkus-catalog:jar"));
        }
        var configured = model.getBuild() == null ? null : model.getBuild().getPluginsAsMap().get(plugin);
        if (application.requires(configured)) {
            requireVersion(plugin, pluginVersion, configured == null ? null : configured.getVersion());
        }
        for (Dependency dep : model.getDependencies()) {
            if ("org.apache.camel".equals(dep.getGroupId()) || "org.apache.camel.springboot".equals(dep.getGroupId())
                    || "org.apache.camel.quarkus".equals(dep.getGroupId())
                    || "org.springframework.boot".equals(dep.getGroupId())) {
                String version = managed.get(dep.getManagementKey());
                if (application.requires(version)) {
                    requireVersion(dep.getManagementKey(), version, dep.getVersion());
                }
            }
        }
    }

    private static String resolvedCore(Model model, byte[] tree) throws IOException {
        String expected = "Expected dependency:tree JSON for the destination module. "
                          + "Use Maven Dependency Plugin 3.7.0 or later with -DoutputType=json.";
        JsonNode root;
        try {
            root = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(tree);
        } catch (JsonProcessingException e) {
            throw new IOException(expected, e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException(expected);
        }
        requireVersion("dependency tree groupId", model.getGroupId(), root.path("groupId").textValue());
        requireVersion("dependency tree artifactId", model.getArtifactId(), root.path("artifactId").textValue());
        requireVersion("dependency tree version", model.getVersion(), root.path("version").textValue());
        var nodes = new ArrayDeque<JsonNode>();
        nodes.add(root);
        String version = null;
        while (!nodes.isEmpty()) {
            JsonNode node = nodes.removeLast();
            if ("org.apache.camel".equals(node.path("groupId").textValue())
                    && "camel-core-engine".equals(node.path("artifactId").textValue())
                    && "jar".equals(node.path("type").textValue()) && node.path("classifier").asText().isEmpty()
                    && ("compile".equals(node.path("scope").textValue())
                            || "runtime".equals(node.path("scope").textValue()))) {
                String found = node.path("version").textValue();
                if (found == null || (version != null && !version.equals(found))) {
                    throw new IOException("Missing or conflicting resolved camel-core-engine versions");
                }
                version = found;
            }
            if (node.has("children") && !node.path("children").isArray()) {
                throw new IOException("Invalid children in Maven dependency tree");
            }
            node.path("children").forEach(nodes::add);
        }
        return version;
    }

    private static void requireVersion(String artifact, String expected, String actual) throws IOException {
        if (expected == null || !expected.equals(actual)) {
            throw new IOException(artifact + ": expected " + expected + ", found " + actual);
        }
    }
}
