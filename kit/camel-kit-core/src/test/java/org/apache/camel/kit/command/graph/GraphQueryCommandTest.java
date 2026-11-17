package org.apache.camel.kit.command.graph;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.CommandRunner;

import org.aesh.command.CommandDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GraphQueryCommandTest {

    @TempDir
    Path tempDir;

    @CommandDefinition(name = "test-query", description = "Test query")
    static class TestQueryCommand extends GraphQueryCommand {
        @Override
        protected String execute(org.apache.camel.kit.graph.ProjectGraph graph) {
            return "{\"ok\":true,\"nodes\":" + graph.nodeCount() + "}";
        }
    }

    @Test
    void missingGraphFileReturnsExitCode1() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        CommandRunner cmd = new CommandRunner(new TestQueryCommand());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        assertEquals(1, cmd.execute("--graph-file", tempDir.resolve("none.json").toString()));
        assertTrue(err.toString().contains("Graph not found"));
    }

    @Test
    void invalidJsonReturnsExitCode1() throws Exception {
        Path bad = tempDir.resolve("bad.json");
        Files.writeString(bad, "not json");
        StringWriter out = new StringWriter(), err = new StringWriter();
        CommandRunner cmd = new CommandRunner(new TestQueryCommand());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        assertEquals(1, cmd.execute("--graph-file", bad.toString()));
        assertTrue(err.toString().contains("Invalid graph file"));
    }

    @Test
    void validGraphReturnsExitCode0() throws Exception {
        Path gf = TestGraphs.writeToTempFile(TestGraphs.sampleProject(), tempDir);
        StringWriter out = new StringWriter(), err = new StringWriter();
        CommandRunner cmd = new CommandRunner(new TestQueryCommand());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        assertEquals(0, cmd.execute("--graph-file", gf.toString()));
        assertTrue(out.toString().contains("\"ok\":true"));
    }
}
