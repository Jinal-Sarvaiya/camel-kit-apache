package org.apache.camel.kit.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Checks Maven's effective dependency management without executing the generated project. */
public final class TargetPomCheck {
    private TargetPomCheck() {
    }

    public static void check(TargetRuntimeProfile target, byte[] effectivePom) throws IOException {
        Model model;
        try {
            model = new MavenXpp3Reader().read(new ByteArrayInputStream(effectivePom));
        } catch (XmlPullParserException e) {
            throw new IOException("Expected a single module's effective POM", e);
        }
        if (target.applicationBom() == null || model.getDependencyManagement() == null) {
            throw new IOException("Expected a Maven destination with dependency management");
        }
        Map<String, String> managed = new HashMap<>();
        for (Dependency dep : model.getDependencyManagement().getDependencies()) {
            managed.put(dep.getManagementKey(), dep.getVersion());
        }
        requireVersion("org.apache.camel:camel-core-engine", target.camelVersion(),
                managed.get("org.apache.camel:camel-core-engine:jar"));
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
            requireVersion("Camel Quarkus catalog", target.runtimeCatalogVersion(),
                    managed.get("org.apache.camel.quarkus:camel-quarkus-catalog:jar"));
        }
        var configured = model.getBuild() == null ? null : model.getBuild().getPluginsAsMap().get(plugin);
        requireVersion(plugin, pluginVersion, configured == null ? null : configured.getVersion());
        for (Dependency dep : model.getDependencies()) {
            if ("org.apache.camel".equals(dep.getGroupId()) || "org.apache.camel.springboot".equals(dep.getGroupId())
                    || "org.apache.camel.quarkus".equals(dep.getGroupId())
                    || "org.springframework.boot".equals(dep.getGroupId())) {
                requireVersion(dep.getManagementKey(), managed.get(dep.getManagementKey()), dep.getVersion());
            }
        }
    }

    private static void requireVersion(String artifact, String expected, String actual) throws IOException {
        if (expected == null || !expected.equals(actual)) {
            throw new IOException(artifact + ": expected " + expected + ", found " + actual);
        }
    }
}
