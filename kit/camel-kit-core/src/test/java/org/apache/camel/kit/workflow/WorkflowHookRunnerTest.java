package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.workflow.WorkflowHookProvider.Check;
import org.apache.camel.kit.workflow.WorkflowHookProvider.Result;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowHookRunnerTest {
    private static final String ID = "step/validate/report/coverage";
    private static final String INPUT = """
            {"sources":["orders.yaml"],"target":"quarkus","artifacts":{"inventory":"coverage.tsv"}}
            """;
    private static SkillCatalog catalog;

    @TempDir
    Path project;
    private Path input;
    private Path submission;
    private WorkflowHookRunner runner;
    private String result;

    @BeforeAll
    static void compileExample(@TempDir Path dist) throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources");
        try (var loader = new URLClassLoader(
                new URL[]{example.toUri().toURL(), dist.toUri().toURL()},
                WorkflowHookRunnerTest.class.getClassLoader())) {
            Path archive = dist.resolve(SkillCatalog.DISTRIBUTION);
            Files.createDirectories(archive.getParent());
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("report-review")));
            catalog = SkillCatalog.load(loader);
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        input = Files.writeString(project.resolve("input.json"), INPUT);
        Files.writeString(project.resolve("orders.yaml"), "orders");
        Files.writeString(project.resolve("coverage.tsv"), "unit\torders.yaml");
        submission = project.resolve("result.json");
        runner = new WorkflowHookRunner(catalog, project);
        var request = runner.run(ID, input, null, null).request();
        result = WorkflowHookRunner.toJson(new Result(
                request, "passed",
                Map.of("route-coverage", new Check(true, "Orders route covered by unit suite")), null));
        Files.writeString(submission, result);
        assertEquals("passed", runner.run(ID, input, submission, null).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sources", "artifacts", "target", "decisions", "catalogSha256", "contribution", "handler", "contract"})
    void rejectsStaleOrReboundEvidenceAndIdentifiesTheDifference(String field) throws Exception {
        switch (field) {
            case "sources" -> Files.writeString(project.resolve("orders.yaml"), "revised");
            case "artifacts" -> Files.writeString(project.resolve("coverage.tsv"), "revised");
            case "target" -> Files.writeString(input, INPUT.replace("quarkus", "spring-boot"));
            default -> {
                var edited = SkillCatalog.JSON.readTree(result);
                var binding = (ObjectNode) edited.required("request");
                if (field.equals("contract")) {
                    binding.put(field, 2);
                } else if (field.equals("decisions")) {
                    binding.putArray(field).addObject().put("question", "Which suite?").put("answer", "unit");
                } else {
                    binding.put(field, "some-other-binding");
                }
                Files.writeString(submission, edited.toString());
            }
        }
        assertRejected(field);
    }

    @Test
    void requiresTheExactChecksAndARealObservation() throws Exception {
        var json = (ObjectNode) SkillCatalog.JSON.readTree(result);
        var checks = json.putObject("checks");
        Files.writeString(submission, json.toString());
        assertRejected("route-coverage");
        var check = checks.putObject("route-coverage");
        check.put("passed", true).put("evidence", "  ");
        Files.writeString(submission, json.toString());
        assertRejected("route-coverage");
        check.put("evidence", "route absent").put("passed", false);
        Files.writeString(submission, json.toString());
        assertRejected("route-coverage failed");
        checks.set("other", check.deepCopy());
        Files.writeString(submission, json.toString());
        assertRejected("undeclared");
    }

    @Test
    void rejectsMalformedWireDataAndIncompleteDecisions() throws Exception {
        for (String malformed : List.of(result + " {}", "null",
                result.replace("\"passed\":true", "\"passed\":\"true\""),
                result.replace("\"passed\":true", "\"passed\":false,\"passed\":true"),
                result.replace("\"status\":\"passed\"", "\"status\":8"),
                result.replace("\"status\":\"passed\"", "\"status\":\"needs-input\""),
                result.replace("\"status\":\"passed\"", "\"status\":\"unknown\""))) {
            Files.writeString(submission, malformed);
            assertThrows(IOException.class, () -> runner.run(ID, input, submission, null), malformed);
        }
    }

    @Test
    void rejectsUnsupportedTargetsMissingArtifactsAndUndeclaredHooks() throws Exception {
        Files.writeString(input, INPUT.replace("quarkus", "unsupported"));
        assertRejected("unsupported target");
        Files.writeString(input, INPUT.replace("inventory", "extra"));
        assertRejected("inventory");
        Files.writeString(input, INPUT.replace("orders.yaml", "../orders.yaml"));
        assertRejected("project-relative");
        Files.writeString(input, INPUT.replace("coverage.tsv", "missing.tsv"));
        assertRejected("missing.tsv");
        for (String id : List.of("skill/camel-migrate", "step/validate/report/timestamp", "provider/missing")) {
            var failure = assertThrows(IOException.class, () -> runner.run(id, input, submission, null));
            assertTrue(failure.getMessage().contains("No executable hook"), failure::getMessage);
        }
    }

    @Test
    void onlyMatchingPendingRequestsCanCarryConfirmedAnswersAcrossMultipleQuestions() throws Exception {
        var initial = runner.run(ID, input, null, null).request();
        Path pending = project.resolve("pending.json");
        Files.writeString(input, INPUT.replace("\"sources\"", "\"answer\":\"integration\",\"sources\""));
        assertRejected("--resume");
        Files.writeString(pending, result);
        assertThrows(IOException.class, () -> runner.run(ID, input, null, pending));
        Files.writeString(pending,
                WorkflowHookRunner.toJson(new Result(initial, "needs-input", Map.of(), "Which suite?")));
        var first = runner.run(ID, input, null, pending).request();
        Files.writeString(pending,
                WorkflowHookRunner.toJson(new Result(first, "needs-input", Map.of(), "Which release?")));
        Files.writeString(input, INPUT.replace("\"sources\"", "\"answer\":\"summer\",\"sources\""));
        var resumed = runner.run(ID, input, null, pending).request();
        assertEquals(List.of(new WorkflowHookProvider.Decision("Which suite?", "integration"),
                new WorkflowHookProvider.Decision("Which release?", "summer")), resumed.decisions());
        Files.writeString(input, Files.readString(input).replace("quarkus", "spring-boot"));
        var stale = assertThrows(IOException.class, () -> runner.run(ID, input, null, pending));
        assertTrue(stale.getMessage().contains("target"), stale::getMessage);
    }

    private void assertRejected(String reason) {
        var failure = assertThrows(IOException.class, () -> runner.run(ID, input, submission, null));
        assertTrue(failure.getMessage().contains(reason), failure::getMessage);
    }
}
