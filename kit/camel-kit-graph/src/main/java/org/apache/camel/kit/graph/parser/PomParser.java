package org.apache.camel.kit.graph.parser;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.model.*;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

public class PomParser implements GraphParser {

    @Override
    public void parse(Path projectRoot, ProjectGraph graph) {
        parseFiles(projectRoot, scannedFilePaths(projectRoot, GraphParser.projectFiles(projectRoot)), graph);
    }

    @Override
    public List<String> scannedFiles(Path projectRoot) {
        return GraphParser.findFiles(projectRoot, file -> file.getFileName().toString().equals("pom.xml"));
    }

    @Override
    public List<Path> scannedFilePaths(Path projectRoot, List<Path> projectFiles) {
        return GraphParser.findFilePaths(projectRoot, projectFiles,
                file -> file.getFileName().toString().equals("pom.xml"));
    }

    @Override
    public void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph) {
        GraphParser.requireSafeFiles(projectRoot, files);
        files.forEach(file -> parsePom(file, projectRoot, graph));
    }

    private void parsePom(Path pomFile, Path projectRoot, ProjectGraph graph) {
        try (Reader reader = new StringReader(
                new String(GraphParser.readSource(pomFile), StandardCharsets.UTF_8))) {
            Model model = new MavenXpp3Reader().read(reader);

            String groupId = model.getGroupId() != null
                    ? model.getGroupId()
                    : (model.getParent() != null ? model.getParent().getGroupId() : "unknown");
            String artifactId = model.getArtifactId();
            String version = model.getVersion() != null
                    ? model.getVersion()
                    : (model.getParent() != null ? model.getParent().getVersion() : "unknown");

            String projectNodeId = "maven:" + groupId + ":" + artifactId;
            graph.addNode(new GraphNode(
                    projectNodeId, NodeType.MAVEN_ARTIFACT,
                    Map.of("groupId", groupId,
                            "artifactId", artifactId,
                            "version", version,
                            "file", projectRoot.relativize(pomFile).toString())));

            // Store Maven properties as CONFIG_PROPERTY nodes (for version detection)
            if (model.getProperties() != null) {
                for (String propName : model.getProperties().stringPropertyNames()) {
                    String propValue = model.getProperties().getProperty(propName);
                    String propNodeId = "maven-property:" + propName;
                    graph.addNode(new GraphNode(
                            propNodeId, NodeType.CONFIG_PROPERTY,
                            Map.of("name", propName, "value", propValue,
                                    "file", projectRoot.relativize(pomFile).toString())));
                }
            }

            // Version evidence from the parent and from dependencyManagement (imported BOMs). These carry the platform
            // version (Spring Boot, Quarkus platform, Camel BOM) that dependencies without a version rely on. They are
            // recorded as separate nodes so they never masquerade as a dependency of the project. Versions are resolved
            // against the declaring POM's own properties to prevent cross-module property collision.
            String pomRelative = projectRoot.relativize(pomFile).toString();
            if (model.getParent() != null) {
                String parentVersion = resolveProperty(model.getParent().getVersion(), model);
                graph.addNode(new GraphNode(
                        "maven-parent:" + model.getParent().getGroupId() + ":" + model.getParent().getArtifactId(),
                        NodeType.CONFIG_PROPERTY,
                        Map.of("kind", "parent",
                                "groupId", model.getParent().getGroupId(),
                                "artifactId", model.getParent().getArtifactId(),
                                "version", parentVersion != null ? parentVersion : "unknown",
                                "file", pomRelative)));
            }
            DependencyManagement management = model.getDependencyManagement();
            if (management != null) {
                for (Dependency managed : management.getDependencies()) {
                    String rawVersion = managed.getVersion();
                    String resolvedVersion = rawVersion != null ? resolveProperty(rawVersion, model) : "managed";
                    graph.addNode(new GraphNode(
                            "maven-managed:" + managed.getGroupId() + ":" + managed.getArtifactId(),
                            NodeType.CONFIG_PROPERTY,
                            Map.of("kind", "managed",
                                    "groupId", managed.getGroupId(),
                                    "artifactId", managed.getArtifactId(),
                                    "version", resolvedVersion,
                                    "scope", managed.getScope() != null ? managed.getScope() : "compile",
                                    "type", managed.getType() != null ? managed.getType() : "jar",
                                    "file", pomRelative)));
                }
            }

            recordCamelVersionEvidence(model, pomRelative, graph);

            for (Dependency dep : model.getDependencies()) {
                String depId = "maven:" + dep.getGroupId() + ":" + dep.getArtifactId();
                String depVersion = dep.getVersion() != null ? dep.getVersion() : "managed";
                String scope = dep.getScope() != null ? dep.getScope() : "compile";

                graph.addNode(new GraphNode(
                        depId, NodeType.MAVEN_ARTIFACT,
                        Map.of("groupId", dep.getGroupId(),
                                "artifactId", dep.getArtifactId(),
                                "version", depVersion,
                                "scope", scope)));

                graph.addEdge(new GraphEdge(
                        projectNodeId, depId,
                        EdgeType.DEPENDS_ON, Map.of()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse POM: " + pomFile, e);
        }
    }

    /**
     * The graph keys properties and artifacts by name, so in a reactor the declarations of several modules collapse
     * into one node and only the last survives. Camel version declarations are therefore also recorded once per POM
     * under {@code maven-camel-version:<pom>:<source>}, with the property references resolved against that POM's own
     * properties. Discovery uses them to notice modules that disagree.
     */
    private void recordCamelVersionEvidence(Model model, String pomRelative, ProjectGraph graph) {
        String camelVersionProperty
                = model.getProperties() == null ? null : model.getProperties().getProperty("camel.version");
        addCamelVersionEvidence(graph, pomRelative, "property camel.version", camelVersionProperty, model);
        for (Dependency dep : model.getDependencies()) {
            if ("org.apache.camel".equals(dep.getGroupId())) {
                addCamelVersionEvidence(graph, pomRelative, "dependency " + dep.getArtifactId(), dep.getVersion(),
                        model);
            }
        }
        DependencyManagement management = model.getDependencyManagement();
        if (management != null) {
            for (Dependency managed : management.getDependencies()) {
                // Only Camel core BOMs (org.apache.camel group) represent Camel core versions.
                // Camel Quarkus extension BOMs (org.apache.camel.quarkus group) have independent versioning.
                if ("pom".equals(managed.getType()) && "org.apache.camel".equals(managed.getGroupId())
                        && managed.getArtifactId() != null
                        && managed.getArtifactId().startsWith("camel-") && managed.getArtifactId().endsWith("-bom")) {
                    addCamelVersionEvidence(graph, pomRelative, "imported BOM " + managed.getArtifactId(),
                            managed.getVersion(), model);
                }
            }
        }
    }

    private void addCamelVersionEvidence(
            ProjectGraph graph, String pomRelative, String source, String rawVersion, Model model) {
        String version = resolveProperty(rawVersion, model);
        if (version == null || version.isBlank() || version.contains("${")) {
            return;
        }
        graph.addNode(new GraphNode(
                "maven-camel-version:" + pomRelative + ":" + source, NodeType.CONFIG_PROPERTY,
                Map.of("kind", "camel-version-evidence", "version", version, "source", source, "file", pomRelative)));
    }

    private static String resolveProperty(String value, Model model) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}") || model.getProperties() == null) {
            return value;
        }
        String resolved = model.getProperties().getProperty(value.substring(2, value.length() - 1));
        return resolved == null ? value : resolved;
    }
}
