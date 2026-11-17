package org.apache.camel.kit.command.doc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class DocReconciliationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    record Result(int code, String out, String err) {
    }

    private Result run(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        var cli = new CommandLine(new DocCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        var arguments = new ArrayList<>(List.of(args));
        arguments.addAll(List.of("--project", project.toString()));
        return new Result(cli.execute(arguments.toArray(String[]::new)), out.toString(), err.toString());
    }

    private Path write(String path, String text) throws Exception {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private void snapshot() throws Exception {
        Result result = run("snapshot", "--source", "apps", "--source", "decisions");
        assertEquals(0, result.code(), result.err());
        write("review-input.json", result.out());
    }

    private void record(String result) {
        Result invocation = run("record", "docs/current.md", "--snapshot", "review-input.json",
                "--evidence", "docs/review.md", "--result", result);
        assertEquals(0, invocation.code(), invocation.err());
    }

    private JsonNode check(String expected) throws Exception {
        Result result = run("check", "docs/current.md");
        assertEquals(0, result.code(), result.err());
        JsonNode node = JSON.readTree(result.out());
        assertEquals(expected, node.path("reconciliation").path("status").asText());
        assertEquals(!expected.equals("unchanged"), node.path("stale").asBoolean());
        return node;
    }

    private void initialSystem() throws Exception {
        write("apps/producer/routes.yaml", "- to: kafka:events\n");
        write("apps/consumer/routes.yaml", "- log: '${header.eventId}'\n");
        write("decisions/0042-no-storage.md",
                "# No payload storage\nStatus: accepted\nContext: limited retention budget.\n");
        write("docs/current.md", "Producer sends to Kafka; consumer logs event IDs. Payloads are not stored.\n");
        write("docs/review.md", "Compared both routes and the accepted decision: event IDs only, no payload sink.\n");
    }

    @Test
    void successiveChangesAndManualDrift() throws Exception {
        initialSystem();
        check("unverified");
        snapshot();
        record("complete");
        check("unchanged");

        // Accepting a successor does not implement the archive or refresh the current description.
        String rationale = Files.readString(project.resolve("decisions/0042-no-storage.md"));
        write("decisions/0043-archive.md", "Status: accepted; implementation: pending\nSupersedes: 0042\n");
        check("stale");
        write("apps/consumer/routes.yaml", "- to: direct:archive\n- log: '${header.eventId}'\n");
        write("apps/archive/routes.yaml", "- from: direct:archive\n- to: file:archive\n");
        write("decisions/0042-no-storage.md", rationale.replace("Status: accepted", "Status: superseded by 0043"));
        snapshot();
        write("docs/current.md",
                "Producer sends to Kafka; consumer archives payloads separately. Logs contain event IDs only.\n");
        write("docs/review.md", "Compared producer, consumer and archive: payload storage is separate from logging.\n");
        record("complete");
        check("unchanged");
        assertTrue(Files.readString(project.resolve("decisions/0042-no-storage.md"))
                .contains("Context: limited retention budget."));

        // No Git commit is involved: additions, edits and deletions all invalidate the review.
        write("apps/consumer/manual.yaml", "- log: '${body}'\n");
        check("stale");
        Files.delete(project.resolve("apps/consumer/manual.yaml"));
        check("unchanged");
        Files.delete(project.resolve("apps/archive/routes.yaml"));
        check("stale");
    }

    @Test
    void partialFailureAndChangedEvidenceCannotBecomeCurrent() throws Exception {
        initialSystem();
        snapshot();
        for (String result : List.of("partial", "failed")) {
            record(result);
            check("unverified");
        }
        record("complete");
        write("docs/review.md", "Tests unavailable.\n");
        check("stale");
        record("partial");
        check("unverified");
    }

    @Test
    void refusesReviewWhenSourcesChangedDuringReview() throws Exception {
        initialSystem();
        snapshot();
        write("apps/consumer/routes.yaml", "- log: '${body}'\n");
        Result result = run("record", "docs/current.md", "--snapshot", "review-input.json",
                "--evidence", "docs/review.md", "--result", "complete");
        assertEquals(1, result.code());
        assertFalse(Files.exists(project.resolve("docs/current.md.reconciliation.json")));
        check("unverified");
    }

    @Test
    void changedDocumentAndClearedFlagDoNotResetEvidence() throws Exception {
        initialSystem();
        snapshot();
        record("complete");
        String original = Files.readString(project.resolve("docs/current.md"));
        write("docs/current.md", "Different description.\n");
        check("stale");
        new CommandLine(new DocUnstaleCommand()).execute(project.resolve("docs/current.md").toString());
        check("stale");
        write("docs/current.md", original);
        check("unchanged");
    }

    @Test
    void rejectsEscapesSymlinksEmptyEvidenceAndMalformedReceipts() throws Exception {
        initialSystem();
        assertEquals(1, run("snapshot", "--source", "../outside").code());
        assertEquals(1, run("snapshot", "--source", "missing").code());
        Path link = project.resolve("apps/linked");
        Files.createSymbolicLink(link, project.resolve("decisions"));
        assertEquals(1, run("snapshot", "--source", "apps").code());
        Files.delete(link);
        snapshot();
        write("docs/review.md", " \n");
        assertEquals(1, run("record", "docs/current.md", "--snapshot", "review-input.json",
                "--evidence", "docs/review.md", "--result", "complete").code());
        write("docs/current.md.reconciliation.json", "{}");
        check("unverified");
        write("docs/current.md.reconciliation.json", "{\"schemaVersion\":1,\"schemaVersion\":2}");
        check("unverified");
    }

    @Test
    void invalidReviewInputsPreservePreviousReceiptAndDocument() throws Exception {
        initialSystem();
        snapshot();
        record("complete");
        Path receipt = project.resolve("docs/current.md.reconciliation.json");
        String saved = Files.readString(receipt);
        String source = Files.readString(project.resolve("review-input.json"));
        for (String invalid : List.of(
                source.replace("\"schemaVersion\" : 1", "\"schemaVersion\" : \"1\""),
                source.replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 1.5"),
                source + " {}", "{}")) {
            write("review-input.json", invalid);
            assertEquals(1, run("record", "docs/current.md", "--snapshot", "review-input.json",
                    "--evidence", "docs/review.md", "--result", "complete").code());
            assertEquals(saved, Files.readString(receipt));
        }
        Files.delete(project.resolve("docs/review.md"));
        check("unverified");
    }

    @Test
    void refusesSelfReferentialScopesAndReceiptSymlinks() throws Exception {
        initialSystem();
        Result result = run("snapshot", "--source", "apps", "--source", "docs");
        assertEquals(0, result.code(), result.err());
        write("review-input.json", result.out());
        assertEquals(1, run("record", "docs/current.md", "--snapshot", "review-input.json",
                "--evidence", "docs/review.md", "--result", "complete").code());
        snapshot();
        Path target = write("user-owned.json", "Preserve me\n");
        Files.createSymbolicLink(project.resolve("docs/current.md.reconciliation.json"), target);
        assertEquals(1, run("record", "docs/current.md", "--snapshot", "review-input.json",
                "--evidence", "docs/review.md", "--result", "complete").code());
        assertEquals("Preserve me\n", Files.readString(target));
        check("unverified");
    }

    @Test
    void boundsSourceAndMetadataReads() throws Exception {
        initialSystem();
        Path large = project.resolve("apps/large.bin");
        try (var file = Files.newByteChannel(large, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
            file.position(64L * 1024 * 1024);
            file.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertEquals(1, run("snapshot", "--source", "apps").code());
        Files.delete(large);
        snapshot();
        write("docs/review.md", "x".repeat(1024 * 1024 + 1));
        assertEquals(1, run("record", "docs/current.md", "--snapshot", "review-input.json",
                "--evidence", "docs/review.md", "--result", "complete").code());
        assertFalse(Files.exists(project.resolve("docs/current.md.reconciliation.json")));
    }
}
