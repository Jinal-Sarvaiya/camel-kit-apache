package org.apache.camel.kit.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.resolution.ModelResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetRuntimeProfileTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SPRING = """
            project.runtime=main
            project.camelVersion=3.0
            target.runtime=spring-boot
            target.camelVersion=4.1
            target.applicationBom=com.vendor.platform:camel-spring-boot-bom:4.2
            target.catalogBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.3
            target.springBootVersion=3.5
            camel.catalog.repos=https://example.org/releases
            """;
    private static final String QUARKUS = """
            target.runtime=quarkus
            target.camelVersion=4.1
            target.applicationBom=io.quarkus.platform:quarkus-camel-bom:3.2
            target.catalogVersion=3.1
            """;

    @Test
    void applicationAndCatalogVersionsAreIndependent() throws Exception {
        var spring = profile(SPRING);
        var quarkus = profile(QUARKUS);
        assertEquals("4.3", spring.runtimeCatalogVersion());
        assertEquals("3.1", quarkus.runtimeCatalogVersion());
        assertEquals("https://example.org/releases", spring.repositories().get(0));
        assertEquals("org.apache.camel.springboot:camel-catalog-provider-springboot:4.3",
                spring.catalog().platformBom());
        assertEquals(quarkus.applicationBom(), quarkus.catalog().platformBom());
    }

    @Test
    void firstMavenModelResolvesAllDependenciesUsingTheSelectedImports() throws Exception {
        for (var profile : new TargetRuntimeProfile[]{profile(SPRING), profile(QUARKUS)}) {
            String pom = profile.pomTemplate().replace("[PROJECT_NAME]", "Test").replace("[PROJECT_GROUP_ID]", "test")
                    .replace("[PROJECT_ARTIFACT_ID]", "app").replace("[PROJECT_VERSION]", "1");
            if ("quarkus".equals(profile.runtime())) {
                pom = pom.replace("<!-- Test -->",
                        dependency("org.apache.camel.quarkus:camel-quarkus-timer", "") + "<!-- Test -->");
            }
            var request = new DefaultModelBuildingRequest().setModelSource(new StringModelSource(pom))
                    .setModelResolver(new FixtureBoms());
            var model = new DefaultModelBuilderFactory().newInstance().build(request).getEffectiveModel();
            var bytes = new ByteArrayOutputStream();
            new MavenXpp3Writer().write(bytes, model);
            new TargetPomCheck(bytes.toByteArray(), null).check(profile, TargetPomCheck.Application.GENERATED);
            for (Dependency dep : model.getDependencies()) {
                assertEquals(FixtureBoms.MANAGED.get(dep.getGroupId() + ":" + dep.getArtifactId()), dep.getVersion(),
                        dep::getManagementKey);
            }
            model.getDependencies().get(0).setVersion("0.1");
            var wrong = new ByteArrayOutputStream();
            new MavenXpp3Writer().write(wrong, model);
            assertThrows(IOException.class, () -> new TargetPomCheck(wrong.toByteArray(), null).check(profile,
                    TargetPomCheck.Application.GENERATED));
        }
    }

    @Test
    void sourceAndDestinationStaySeparateAndInvalidCoordinatesAreRejected() throws Exception {
        var config = new Properties();
        config.load(new StringReader(SPRING));
        assertEquals("3.0", TargetRuntimeProfile.resolve(config, false).camelVersion());
        config.setProperty("camel.catalog.repos", "https://user:secret@example.org/releases");
        var invalid = assertThrows(IllegalArgumentException.class, () -> TargetRuntimeProfile.resolve(config, true));
        assertFalse(invalid.getMessage().contains("secret"));
        config.setProperty("camel.catalog.repos", "https://example.org/releases, ");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TargetRuntimeProfile.resolve(config, true))
                .getMessage().contains("Empty repository"));
        config.remove("camel.catalog.repos");
        config.remove("target.catalogBom");
        assertThrows(IllegalArgumentException.class, () -> TargetRuntimeProfile.resolve(config, true));
        config.remove("target.runtime");
        assertThrows(IllegalArgumentException.class, () -> TargetRuntimeProfile.resolve(config, true));
    }

    @Test
    void greenfieldDefaultsUseTheSelectedCommunityBom() {
        var config = new Properties();
        config.setProperty("project.runtime", "spring-boot");
        config.setProperty("project.camelVersion", "4.22.1");
        config.setProperty("project.platformBomVersion", "4.22.1");
        config.setProperty("camel.catalog.repos", "central=https://example.org/a, https://example.org/b");
        var spring = TargetRuntimeProfile.resolve(config, false);
        assertEquals("org.apache.camel.springboot:camel-spring-boot-bom:4.22.1", spring.applicationBom());
        assertEquals("https://example.org/b", spring.repositories().get(1));
        config.setProperty("project.runtime", "quarkus");
        config.setProperty("project.platformBomVersion", "3.33.3.2");
        var quarkus = TargetRuntimeProfile.resolve(config, false);
        assertEquals("io.quarkus.platform:quarkus-camel-bom:3.33.3.2", quarkus.catalog().platformBom());
        assertTrue(assertThrows(IllegalArgumentException.class, quarkus::requiredCatalogVersion)
                .getMessage().contains("project.catalogVersion"));
        config.setProperty("project.type", "existing");
        assertThrows(IllegalArgumentException.class, () -> TargetRuntimeProfile.resolve(config, false));
    }

    @Test
    void rejectsFieldsFromAnotherRuntimeOrBom() {
        assertThrows(IllegalArgumentException.class, () -> profile(QUARKUS + "target.springBootVersion=3.5"));
        assertThrows(IllegalArgumentException.class, () -> profile(SPRING + "target.platformBomVersion=4.4"));
    }

    @Test
    void acceptsBothRuntimeCatalogResponses() throws Exception {
        for (String config : new String[]{SPRING, QUARKUS}) {
            var profile = profile(config);
            TargetCatalogEvidence.check(profile, JSON.writeValueAsBytes(evidence(profile)));
        }
    }

    @Test
    void rejectsStaleOrMissingCatalogEvidence() throws Exception {
        var profile = profile(SPRING);
        var evidence = evidence(profile);
        var changed = profile(SPRING + "camel.catalog.repos=https://another.example/releases");
        assertThrows(IOException.class, () -> TargetCatalogEvidence.check(changed, JSON.writeValueAsBytes(evidence)));
        evidence.remove("maven");
        assertThrows(IOException.class, () -> TargetCatalogEvidence.check(profile, JSON.writeValueAsBytes(evidence)));
    }

    @Test
    void detectsCoreAndRuntimeArtifactMismatchesIndependently() throws Exception {
        var profile = profile(QUARKUS);
        for (String key : new String[]{"list", "maven"}) {
            var evidence = evidence(profile);
            ObjectNode result = (ObjectNode) evidence.at("/" + key + "/result/content/0");
            result.put("text", result.path("text").asText().replace("4.1", "4.9").replace("3.1", "3.9"));
            assertThrows(IOException.class,
                    () -> TargetCatalogEvidence.check(profile, JSON.writeValueAsBytes(evidence)));
        }
    }

    private static ObjectNode evidence(TargetRuntimeProfile profile) {
        ObjectNode args = JSON.valueToTree(profile.catalog());
        ObjectNode evidence = JSON.createObjectNode();
        evidence.set("profile", JSON.valueToTree(profile));
        call(evidence, "list", "camel_catalog_components",
                args.deepCopy().put("filter", "timer").put("label", "").put("limit", 2),
                """
                        {"count":1,"camelVersion":"4.1","components":[{"name":"timer"}]}
                        """);
        boolean spring = profile.runtime().equals("spring-boot");
        var timer = JSON.createObjectNode().put("name", "timer")
                .put("groupId", spring ? "org.apache.camel.springboot" : "org.apache.camel.quarkus")
                .put("artifactId", spring ? "camel-timer-starter" : "camel-quarkus-timer")
                .put("version", profile.runtimeCatalogVersion());
        call(evidence, "maven", "camel_catalog_component_maven", args.deepCopy().put("component", "timer"),
                timer.toString());
        return evidence;
    }

    private static void call(ObjectNode evidence, String key, String name, ObjectNode args, String response) {
        var call = evidence.putObject(key).put("name", name);
        call.set("arguments", args);
        call.putObject("result").put("isError", false).putArray("content").addObject().put("type", "text")
                .put("text", response);
    }

    private static TargetRuntimeProfile profile(String text) throws IOException {
        var properties = new Properties();
        properties.load(new StringReader(text));
        return TargetRuntimeProfile.resolve(properties, true);
    }

    private static String dependency(String ga, String version) {
        String[] pair = ga.split(":");
        return "<dependency><groupId>" + pair[0] + "</groupId><artifactId>" + pair[1] + "</artifactId>"
               + (version.isEmpty() ? "" : "<version>" + version + "</version>") + "</dependency>";
    }

    /** Only these published fixture versions exist. A wrong import fails while Maven builds the first model. */
    private static final class FixtureBoms implements ModelResolver {
        static final Map<String, String> MANAGED = Map.of(
                "org.springframework.boot:spring-boot", "3.5", "org.apache.camel:camel-core-engine", "4.1",
                "org.apache.camel.quarkus:camel-quarkus-catalog", "3.1",
                "org.apache.camel.springboot:camel-spring-boot-starter", "4.3",
                "org.apache.camel:camel-test-spring-junit5", "4.1",
                "org.springframework.boot:spring-boot-starter", "3.5",
                "org.springframework.boot:spring-boot-starter-test", "3.5",
                "io.quarkus:quarkus-junit5", "3.2", "org.apache.camel.quarkus:camel-quarkus-timer", "3.1");

        @Override
        public ModelSource resolveModel(String group, String artifact, String version) {
            String prefix = switch (group + ":" + artifact + ":" + version) {
                case "com.vendor.platform:camel-spring-boot-bom:4.2" -> "org.apache.camel.springboot:";
                case "org.apache.camel:camel-bom:4.1" -> "org.apache.camel:";
                case "org.springframework.boot:spring-boot-dependencies:3.5" -> "org.springframework.boot:";
                case "io.quarkus.platform:quarkus-camel-bom:3.2" -> "org.apache.camel.quarkus:";
                case "io.quarkus.platform:quarkus-bom:3.2" -> "io.quarkus:";
                default -> throw new AssertionError("Unexpected import: " + group + ":" + artifact + ":" + version);
            };
            var dependencies = new StringBuilder();
            MANAGED.forEach((ga, v) -> {
                if (ga.startsWith(prefix)
                        || (artifact.equals("quarkus-camel-bom") && ga.equals("org.apache.camel:camel-core-engine"))) {
                    dependencies.append(dependency(ga, v));
                }
            });
            return new StringModelSource(
                    "<project><modelVersion>4.0.0</modelVersion><groupId>" + group
                                         + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                                         + "</version><packaging>pom</packaging>"
                                         + "<dependencyManagement><dependencies>" + dependencies
                                         + "</dependencies></dependencyManagement></project>",
                    group + ":" + artifact);
        }

        @Override
        public ModelSource resolveModel(Dependency dep) {
            return resolveModel(dep.getGroupId(), dep.getArtifactId(), dep.getVersion());
        }

        @Override
        public ModelSource resolveModel(Parent parent) {
            throw new AssertionError("Unexpected parent");
        }

        @Override
        public void addRepository(Repository repository) {
        }

        @Override
        public void addRepository(Repository repository, boolean replace) {
        }

        @Override
        public ModelResolver newCopy() {
            return this;
        }
    }
}
