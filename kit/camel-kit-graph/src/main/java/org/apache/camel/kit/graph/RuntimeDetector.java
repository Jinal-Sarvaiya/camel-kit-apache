package org.apache.camel.kit.graph;

import org.apache.camel.kit.graph.model.GraphNode;
import org.apache.camel.kit.graph.model.NodeType;

public final class RuntimeDetector {

    private RuntimeDetector() {
    }

    public static String detect(ProjectGraph graph) {
        for (GraphNode artifact : graph.findByType(NodeType.MAVEN_ARTIFACT)) {
            String artifactId = artifact.properties().getOrDefault("artifactId", "");
            if (artifactId.startsWith("camel-spring-boot")) {
                return "spring-boot";
            }
            if (artifactId.startsWith("camel-quarkus")) {
                return "quarkus";
            }
            if ("camel-blueprint".equals(artifactId)) {
                return "karaf";
            }
        }
        return "main";
    }

    public static String findMavenProperty(ProjectGraph graph, String propertyName) {
        var node = graph.getNode("maven-property:" + propertyName);
        if (node != null) {
            String value = node.properties().get("value");
            if (value != null && !value.isEmpty() && !value.contains("${")) {
                return value;
            }
        }
        return null;
    }

    public static String findArtifactVersion(ProjectGraph graph, String groupId, String artifactId) {
        String nodeId = "maven:" + groupId + ":" + artifactId;
        var node = graph.getNode(nodeId);
        if (node != null) {
            String version = node.properties().get("version");
            if (version != null && !"managed".equals(version) && !"unknown".equals(version)
                    && !version.contains("${")) {
                return version;
            }
        }
        return null;
    }
}
