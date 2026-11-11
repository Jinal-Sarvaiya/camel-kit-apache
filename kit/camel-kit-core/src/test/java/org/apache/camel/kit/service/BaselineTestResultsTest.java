package org.apache.camel.kit.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorded baseline stores each test's own outcome and the revision it belongs to, so validation can tell a
 * pre-existing failure from a regression by test identity instead of by comparing totals.
 */
class BaselineTestResultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void collectsEveryTestOutcomeFromSurefireReportsIncludingModules() throws Exception {
        writeReport(tempDir.resolve("target/surefire-reports"), "com.example.OrderTest", """
                <testsuite name="com.example.OrderTest" tests="4">
                  <testcase classname="com.example.OrderTest" name="passes"/>
                  <testcase classname="com.example.OrderTest" name="fails">
                    <failure message="expected 1 but was 2" type="AssertionError"/>
                  </testcase>
                  <testcase classname="com.example.OrderTest" name="errors">
                    <error message="boom" type="IllegalStateException"/>
                  </testcase>
                  <testcase classname="com.example.OrderTest" name="skipped"><skipped/></testcase>
                </testsuite>
                """);
        writeReport(tempDir.resolve("module-a/target/surefire-reports"), "com.example.ModuleTest", """
                <testsuite name="com.example.ModuleTest" tests="1">
                  <testcase classname="com.example.ModuleTest" name="passes"/>
                </testsuite>
                """);

        Map<String, BaselineTestResults.Outcome> tests = BaselineTestResults.collect(tempDir);

        assertEquals(5, tests.size());
        assertEquals("passed", tests.get("com.example.OrderTest#passes").status());
        assertEquals("failed", tests.get("com.example.OrderTest#fails").status());
        assertEquals("expected 1 but was 2", tests.get("com.example.OrderTest#fails").reason());
        assertEquals("error", tests.get("com.example.OrderTest#errors").status());
        assertEquals("skipped", tests.get("com.example.OrderTest#skipped").status());
        assertEquals("passed", tests.get("com.example.ModuleTest#passes").status());
    }

    @Test
    void ignoresReportsOutsideBuildOutputAndUnreadableReports() throws Exception {
        writeReport(tempDir.resolve("src/fixtures"), "com.example.NotARealTest", """
                <testsuite><testcase classname="com.example.NotARealTest" name="x"/></testsuite>
                """);
        writeReport(tempDir.resolve("target/surefire-reports"), "com.example.Broken", "<testsuite><testcase");

        assertTrue(BaselineTestResults.collect(tempDir).isEmpty(),
                "only well-formed reports below target/surefire-reports or target/failsafe-reports count");
    }

    @Test
    void doctypeDeclarationsInReportsAreRejected() throws Exception {
        writeReport(tempDir.resolve("target/surefire-reports"), "com.example.XxeTest", """
                <!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <testsuite><testcase classname="com.example.XxeTest" name="&x;"/></testsuite>
                """);

        assertTrue(BaselineTestResults.collect(tempDir).isEmpty(), "external entities must never be resolved");
    }

    @Test
    void jsonCarriesRevisionPerTestOutcomesAndTheNonPassingSummary() throws Exception {
        Map<String, BaselineTestResults.Outcome> tests = new java.util.TreeMap<>();
        tests.put("com.example.A#ok", new BaselineTestResults.Outcome("passed", ""));
        tests.put("com.example.A#bad", new BaselineTestResults.Outcome("failed", "nope"));

        JsonNode json = MAPPER.readTree(BaselineTestResults.toJson("failed", "reason", "abc123", true, tests));

        assertEquals("failed", json.get("status").asText());
        assertEquals("abc123", json.get("revision").asText());
        assertTrue(json.get("dirty").asBoolean());
        assertEquals(2, json.get("testCount").asInt());
        assertEquals("passed", json.at("/tests/com.example.A#ok/status").asText());
        assertEquals("nope", json.at("/tests/com.example.A#bad/reason").asText());
        assertEquals(1, json.get("nonPassing").size());
        assertEquals("com.example.A#bad", json.get("nonPassing").get(0).asText());
    }

    @Test
    void statusOnlyBaselinesHaveTheSameShapeWithAnEmptyTestList() throws Exception {
        JsonNode json = MAPPER.readTree(
                BaselineTestResults.toJson("not_run", "not run", "unknown", true, Map.of()));

        assertEquals("not_run", json.get("status").asText());
        assertEquals(0, json.get("testCount").asInt());
        assertTrue(json.get("tests").isObject());
        assertEquals(0, json.get("tests").size());
        assertEquals(0, json.get("nonPassing").size());
    }

    private static void writeReport(Path directory, String className, String content) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("TEST-" + className + ".xml"), content);
    }
}
