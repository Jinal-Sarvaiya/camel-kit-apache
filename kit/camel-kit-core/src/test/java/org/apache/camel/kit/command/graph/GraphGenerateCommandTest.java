package org.apache.camel.kit.command.graph;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.CommandRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GraphGenerateCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesGraphFile() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>test</artifactId><version>1.0</version></project>");
        Path out = tempDir.resolve(".camel-kit/project-graph.json");
        StringWriter err = new StringWriter();
        CommandRunner cl = new CommandRunner(new GraphGenerateCommand());
        cl.setErr(new PrintWriter(err));
        assertEquals(0, cl.execute("--project-dir", tempDir.toString(), "-o", out.toString()));
        assertTrue(Files.exists(out));
    }

    @Test
    void customOutputPath() throws Exception {
        Path out = tempDir.resolve("custom/graph.json");
        StringWriter err = new StringWriter();
        CommandRunner cl = new CommandRunner(new GraphGenerateCommand());
        cl.setErr(new PrintWriter(err));
        assertEquals(0, cl.execute("--project-dir", tempDir.toString(), "-o", out.toString()));
        assertTrue(Files.exists(out));
    }
}
