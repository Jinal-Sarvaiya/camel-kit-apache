package org.apache.camel.kit.command.graph;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.CommandRunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GraphVisualizeCommandTest {
    @TempDir
    Path tempDir;
    Path graphFile;

    @BeforeEach
    void setUp() throws Exception {
        graphFile = TestGraphs.writeToTempFile(TestGraphs.sampleProject(), tempDir);
    }

    @Test
    void generatesHtmlWithDefaultLibrary() throws Exception {
        Path out = tempDir.resolve("graph.html");
        StringWriter err = new StringWriter();
        CommandRunner cl = new CommandRunner(new GraphVisualizeCommand());
        cl.setErr(new PrintWriter(err));
        assertEquals(0, cl.execute("-i", graphFile.toString(), "-o", out.toString()));
        assertTrue(Files.exists(out));
        String html = Files.readString(out);
        assertTrue(html.contains("cytoscape"));
        assertTrue(html.contains("order-process"));
    }

    @Test
    void generatesHtmlWithD3() throws Exception {
        Path out = tempDir.resolve("d3.html");
        StringWriter err = new StringWriter();
        CommandRunner cl = new CommandRunner(new GraphVisualizeCommand());
        cl.setErr(new PrintWriter(err));
        assertEquals(0, cl.execute("-i", graphFile.toString(), "-o", out.toString(), "-l", "d3"));
        assertTrue(Files.readString(out).contains("d3.forceSimulation"));
    }

    @Test
    void missingInputReturnsError() {
        StringWriter err = new StringWriter();
        CommandRunner cl = new CommandRunner(new GraphVisualizeCommand());
        cl.setErr(new PrintWriter(err));
        assertEquals(1, cl.execute("-i", tempDir.resolve("none.json").toString()));
        assertTrue(err.toString().contains("Graph not found"));
    }
}
