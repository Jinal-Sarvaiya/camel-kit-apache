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
}
