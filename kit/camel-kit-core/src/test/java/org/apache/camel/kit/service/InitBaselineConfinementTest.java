package org.apache.camel.kit.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.output.Printer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An existing-project init writes the recorded baseline and merges managed instruction files. Neither may follow a
 * symbolic link out of the project, and neither may write through a hard link to a file outside it.
 */
class InitBaselineConfinementTest {

    private static final List<String> BASELINE_FILES = List.of(
            ".camel-kit/baseline-routes.txt", ".camel-kit/baseline-tests.txt",
            ".camel-kit/baseline-contracts.txt", ".camel-kit/baseline-test-results.json");

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {
            ".camel-kit/baseline-routes.txt", ".camel-kit/baseline-tests.txt",
            ".camel-kit/baseline-contracts.txt", ".camel-kit/baseline-test-results.json"})
    void baselineOutputsCannotBeRedirectedThroughSymbolicLinks(String relative) throws Exception {
        Path project = existingProject("symlink");
        Path sentinel = Files.writeString(tempDir.resolve("outside-" + relative.replace('/', '_')), "untouched");
        Path link = project.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, sentinel);

        assertThrows(IOException.class, () -> new InitService().initialize(request(project)),
                "init must refuse before writing anything through " + relative);

        assertEquals("untouched", Files.readString(sentinel));
        assertTrue(Files.isSymbolicLink(link), "the link itself must be left in place");
    }

    @Test
    void aSymbolicLinkInOneBaselineOutputLeavesTheOtherOutsideFilesUntouched() throws Exception {
        Path project = existingProject("all-links");
        for (String relative : BASELINE_FILES) {
            Path sentinel = Files.writeString(tempDir.resolve("outside-" + relative.replace('/', '_')), "untouched");
            Path link = project.resolve(relative);
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, sentinel);
        }

        assertThrows(IOException.class, () -> new InitService().initialize(request(project)));

        for (String relative : BASELINE_FILES) {
            assertEquals("untouched", Files.readString(tempDir.resolve("outside-" + relative.replace('/', '_'))),
                    relative + " must not have been written through its link");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".camel-kit/baseline-routes.txt", ".camel-kit/baseline-tests.txt",
            ".camel-kit/baseline-contracts.txt", ".camel-kit/baseline-test-results.json"})
    void baselineOutputsReplaceHardLinksWithoutChangingTheOutsideFile(String relative) throws Exception {
        Path project = existingProject("hardlink");
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        Path generated = project.resolve(relative);
        Files.createDirectories(generated.getParent());
        Files.createLink(generated, sentinel);

        new InitService().initialize(request(project));

        assertEquals("untouched", Files.readString(sentinel), "the outside file must never be written");
        assertTrue(Files.isRegularFile(generated));
        assertFalse(Files.isSameFile(generated, sentinel), "the entry must be replaced, not written through");
        assertNotEquals("untouched", Files.readString(generated));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENTS.md", "CLAUDE.md"})
    void managedInstructionFilesReplaceHardLinksWithoutChangingTheOutsideFile(String relative) throws Exception {
        Path project = existingProject("instruction-hardlink");
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "# my own notes\n");
        Path generated = project.resolve(relative);
        Files.createLink(generated, sentinel);

        new InitService().initialize(request(project));

        assertEquals("# my own notes\n", Files.readString(sentinel),
                "a hard link to a file outside the project must not be modified");
        assertFalse(Files.isSameFile(generated, sentinel), "the merged file must be a new inode");
        assertTrue(Files.readString(generated).contains("# my own notes"),
                "the user's existing content must be preserved in the replacement");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENTS.md", "CLAUDE.md"})
    void managedInstructionFilesCannotBeRedirectedThroughSymbolicLinks(String relative) throws Exception {
        Path project = existingProject("instruction-symlink");
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "# untouched\n");
        Path link = project.resolve(relative);
        Files.createSymbolicLink(link, sentinel);

        assertThrows(IOException.class, () -> new InitService().initialize(request(project)));

        assertEquals("# untouched\n", Files.readString(sentinel));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void greenfieldInitDoesNotPreflightBaselineOutputs() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("greenfield"));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        Files.createDirectories(project.resolve(".camel-kit"));
        Files.createSymbolicLink(project.resolve(".camel-kit/baseline-routes.txt"), sentinel);

        new InitService().initialize(request(project));

        assertEquals("untouched", Files.readString(sentinel),
                "greenfield init writes no baseline, so the unrelated link is neither followed nor rejected");
    }

    /** A project with a pom, YAML routes, a test and a contract file, so every baseline output is produced. */
    private Path existingProject(String name) throws IOException {
        Path project = Files.createDirectory(tempDir.resolve(name));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>app</artifactId><version>1</version>
                  <properties><camel.version>4.8.0</camel.version></properties>
                  <dependencies>
                    <dependency><groupId>org.apache.camel</groupId><artifactId>camel-core</artifactId>
                      <version>${camel.version}</version></dependency>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId>
                      <version>4.13</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """);
        Path routes = Files.createDirectories(project.resolve("src/main/resources/camel"));
        Files.writeString(routes.resolve("r.yaml"), """
                - route:
                    id: r1
                    from:
                      uri: timer:t
                      steps:
                        - log: hi
                """);
        Path tests = Files.createDirectories(project.resolve("src/test/java/x"));
        Files.writeString(tests.resolve("RoutesTest.java"), "package x; class RoutesTest {}");
        Files.writeString(project.resolve("src/main/resources/orders.xsd"), "<xs:schema/>");
        return project;
    }

    private InitRequest request(Path project) {
        return new InitRequest(
                "orders", "claude", project, "default", true, false, null, "camel-kit", "5.0.1",
                CamelKitMain.distribution(), Printer.noop(), InitProgress.noop(), InitReporter.noop());
    }
}
