package org.apache.camel.kit.command;

import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.output.SystemPrinter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TargetCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    Path dir;

    private String run(int status, String... options) {
        var args = new ArrayList<>(List.of("target", "--project-dir", dir.toString()));
        args.addAll(List.of(options));
        return execute(status, args.toArray(String[]::new));
    }

    private String execute(int status, String... argv) {
        var output = new StringWriter();
        var main = new CamelKitMain(new SystemPrinter());
        try {
            var writer = new PrintWriter(output, true);
            int result = CamelKitMain.commandLine(main, argv).setOut(writer).setErr(writer).execute(argv);
            assertEquals(status, result, output::toString);
            return output.toString();
        } finally {
            main.closeTerminal();
        }
    }

    private void config(String fields) throws Exception {
        Files.createDirectories(dir.resolve(".camel-kit"));
        Files.writeString(dir.resolve(".camel-kit/config.properties"), fields);
    }

    @Test
    void mainAcceptsEvidenceInTheDocumentedShape() throws Exception {
        execute(0, "init", dir.toString(), "--ai", "claude", "--no-fetch");
        String guide = Files.readString(dir.resolve(".claude/skills/shared/mcp-setup.md"));
        String example = guide.substring(guide.indexOf("### Rule 3:"));
        int start = example.indexOf("```json") + 7;
        ObjectNode wire = (ObjectNode) JSON.readTree(example.substring(start, example.indexOf("```", start)));
        var profile = JSON.readTree(run(0));
        // The example repository list is illustrative. Use the repositories configured in this workspace.
        ((ObjectNode) wire.get("profile")).set("repositories", profile.get("repositories"));
        assertEquals(profile, wire.get("profile"));

        String core = profile.get("camelVersion").asText();
        var list = JSON.createObjectNode().put("camelVersion", core).put("count", 1);
        list.putArray("components").addObject().put("name", "timer");
        var artifact = JSON.createObjectNode().put("name", "timer").put("groupId", "org.apache.camel")
                .put("artifactId", "camel-timer").put("version", core);
        ((ObjectNode) wire.at("/list/result/content/0")).put("text", list.toString());
        ((ObjectNode) wire.at("/maven/result/content/0")).put("text", artifact.toString());
        Path file = dir.resolve("evidence.json");
        JSON.writeValue(file.toFile(), wire);
        run(0, "--check-catalog", file.toString());
        ((ObjectNode) wire.get("list")).remove("result");
        JSON.writeValue(file.toFile(), wire);
        assertTrue(run(1, "--check-catalog", "evidence.json").contains("INCOMPLETE"));
    }

    @Test
    void reportsMissingWorkspaceAndRejectsConflictingOrOversizedInputs() throws Exception {
        assertTrue(run(1).contains("Run init"));
        String conflict = run(2, "--pom-template", "--check-catalog", "data");
        assertTrue(conflict.contains("Choose template"));
        assertFalse(conflict.contains("INCOMPLETE"));
        assertTrue(run(2, "--dependency-tree", "tree").contains("requires --check-effective-pom"));
        config("project.runtime=main\nproject.camelVersion=4.22.1\n");
        try (var data = new RandomAccessFile(dir.resolve("data").toFile(), "rw")) {
            data.setLength(65537);
            assertTrue(run(1, "--check-catalog", "data").contains("65536"));
            data.setLength(16 * 1024 * 1024 + 1);
            assertTrue(run(1, "--check-effective-pom", "data").contains("16777216"));
        }
        assertTrue(run(1, "--check-catalog", dir.resolveSibling("outside").toString()).contains("outside"));
        assertTrue(run(1, "--check-catalog", "../outside").contains("outside"));
    }

    @Test
    void rejectsLinkedEvidence() throws Exception {
        config("project.runtime=main\nproject.camelVersion=4.22.1\n");
        try {
            Files.createSymbolicLink(dir.resolve("link"), dir.resolve(".camel-kit/config.properties"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
        }
        assertTrue(run(1, "--check-catalog", "link").contains("symbolic link"));
    }

    @Test
    void recoversTransitiveCoreAndChecksBothInputsForTheSameModule() throws Exception {
        String fields = "project.type=existing\nproject.needsResolvedCore=true\nproject.runtime=spring-boot\n"
                        + "project.applicationBom=org.apache.camel.springboot:camel-spring-boot-bom:4.2\n";
        config(fields);
        Files.writeString(dir.resolve("model"),
                """
                        <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version>
                        <dependencyManagement><dependencies>
                        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot</artifactId><version>3.5</version></dependency>
                        <dependency><groupId>org.apache.camel.springboot</groupId><artifactId>camel-spring-boot-starter</artifactId><version>4.2</version></dependency>
                        </dependencies></dependencyManagement></project>
                        """);
        String tree = """
                {"groupId":"example","artifactId":"app","version":"1","children":[{"groupId":"org.apache.camel",
                 "artifactId":"camel-core-engine","type":"jar","version":"4.1","scope":"compile"}]}
                """;
        Files.writeString(dir.resolve("tree"), tree);
        assertTrue(run(1).contains("--dependency-tree"));
        assertTrue(run(1, "--check-effective-pom", "model").contains("transitive"));
        var profile = JSON.readTree(run(0, "--check-effective-pom", "model", "--dependency-tree", "tree"));
        assertEquals("4.1", profile.get("camelVersion").asText());
        assertEquals("3.5", profile.get("springBootVersion").asText());
        assertEquals(fields, Files.readString(dir.resolve(".camel-kit/config.properties")));
        Files.writeString(dir.resolve("catalog"), "{}");
        assertTrue(run(1, "--check-effective-pom", "model", "--dependency-tree", "tree", "--check-catalog", "catalog")
                .contains("Catalog evidence"));
        Files.writeString(dir.resolve("tree"), "example:app:jar:1");
        String textTree = run(1, "--check-effective-pom", "model", "--dependency-tree", "tree");
        assertTrue(textTree.contains("Expected dependency:tree JSON"));
        assertTrue(textTree.contains("Maven Dependency Plugin 3.7.0 or later with -DoutputType=json"));
        Files.writeString(dir.resolve("tree"), tree.replace("example", "other"));
        assertTrue(run(1, "--check-effective-pom", "model", "--dependency-tree", "tree").contains("tree groupId"));
        Files.writeString(dir.resolve("tree"), tree.replace("compile", "test"));
        assertTrue(run(1, "--check-effective-pom", "model", "--dependency-tree", "tree").contains("No runtime"));
    }
}
