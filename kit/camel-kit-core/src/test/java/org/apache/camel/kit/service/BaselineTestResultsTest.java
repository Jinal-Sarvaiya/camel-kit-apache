package org.apache.camel.kit.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

        var result = BaselineTestResults.collect(tempDir, Instant.MIN);

        assertTrue(result.complete(), "all reports should be parseable");
        Map<String, BaselineTestResults.Outcome> tests = result.outcomes();
        assertEquals(5, tests.size());
        // Root module tests have no prefix
        assertEquals("passed", tests.get("com.example.OrderTest#passes").status());
        assertEquals("failed", tests.get("com.example.OrderTest#fails").status());
        assertEquals("expected 1 but was 2", tests.get("com.example.OrderTest#fails").reason());
        assertEquals("error", tests.get("com.example.OrderTest#errors").status());
        assertEquals("skipped", tests.get("com.example.OrderTest#skipped").status());
        // Module tests have module prefix
        assertEquals("passed", tests.get("module-a:com.example.ModuleTest#passes").status());
    }

    @Test
    void ignoresReportsOutsideBuildOutputAndUnreadableReports() throws Exception {
        writeReport(tempDir.resolve("src/fixtures"), "com.example.NotARealTest", """
                <testsuite><testcase classname="com.example.NotARealTest" name="x"/></testsuite>
                """);
        writeReport(tempDir.resolve("target/surefire-reports"), "com.example.Broken", "<testsuite><testcase");

        var result = BaselineTestResults.collect(tempDir, Instant.MIN);
        assertTrue(result.outcomes().isEmpty(),
                "only well-formed reports below target/surefire-reports or target/failsafe-reports count");
        assertFalse(result.complete(), "broken report should mark collection as incomplete");
        assertEquals(1, result.failedReportCount());
    }

    @Test
    void doctypeDeclarationsInReportsAreRejected() throws Exception {
        writeReport(tempDir.resolve("target/surefire-reports"), "com.example.XxeTest", """
                <!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <testsuite><testcase classname="com.example.XxeTest" name="&x;"/></testsuite>
                """);

        var result = BaselineTestResults.collect(tempDir, Instant.MIN);
        assertTrue(result.outcomes().isEmpty(), "external entities must never be resolved");
        assertFalse(result.complete(), "XXE attack attempt should fail parsing and mark incomplete");
    }

    @Test
    void rejectsStaleReportsFromPreviousInvocations() throws Exception {
        // Write a stale report from a previous run (dated 2020)
        Path staleDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(staleDir);
        Path staleReport = staleDir.resolve("TEST-com.example.OldTest.xml");
        Files.writeString(staleReport, """
                <testsuite name="com.example.OldTest" tests="1">
                  <testcase classname="com.example.OldTest" name="oldFailure">
                    <failure message="old bug from 2020"/>
                  </testcase>
                </testsuite>
                """);
        // Backdate it to 2020
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Files.setLastModifiedTime(staleReport, FileTime.from(old));

        // Simulate capturing timestamp before running tests
        Instant testStartTime = Instant.now().minus(1, ChronoUnit.SECONDS);

        // Simulate a test run that produces no new reports (build failure, tests skipped, etc.)
        // Only the stale report exists, but it predates testStartTime

        var result = BaselineTestResults.collect(tempDir, testStartTime);

        assertTrue(result.outcomes().isEmpty(),
                "stale reports from before the test invocation must be rejected");
        assertTrue(result.complete(), "no parse failures when reports are just stale");
    }

    @Test
    void includesFreshReportsAndRejectsStaleOnes() throws Exception {
        // Write a stale report from a previous run
        Path reportDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reportDir);
        Path staleReport = reportDir.resolve("TEST-com.example.OldTest.xml");
        Files.writeString(staleReport, """
                <testsuite name="com.example.OldTest" tests="1">
                  <testcase classname="com.example.OldTest" name="oldTest">
                    <failure message="stale"/>
                  </testcase>
                </testsuite>
                """);
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Files.setLastModifiedTime(staleReport, FileTime.from(old));

        // Capture timestamp before simulated test run
        Instant testStartTime = Instant.now().minus(1, ChronoUnit.SECONDS);

        // Write a fresh report from the current invocation
        Path freshReport = reportDir.resolve("TEST-com.example.NewTest.xml");
        Files.writeString(freshReport, """
                <testsuite name="com.example.NewTest" tests="1">
                  <testcase classname="com.example.NewTest" name="newTest"/>
                </testsuite>
                """);

        var result = BaselineTestResults.collect(tempDir, testStartTime);
        Map<String, BaselineTestResults.Outcome> collected = result.outcomes();

        assertTrue(result.complete(), "both reports are well-formed");
        assertEquals(1, collected.size(), "only fresh reports from current invocation are included");
        assertNotNull(collected.get("com.example.NewTest#newTest"));
        assertNull(collected.get("com.example.OldTest#oldTest"), "stale report must be excluded");
    }

    @Test
    void marksCollectionIncompleteWhenMixingValidAndInvalidReports() throws Exception {
        Path reportDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reportDir);

        // Write one valid report
        Files.writeString(reportDir.resolve("TEST-com.example.PassingTest.xml"), """
                <testsuite name="com.example.PassingTest" tests="1">
                  <testcase classname="com.example.PassingTest" name="validTest"/>
                </testsuite>
                """);

        // Write one malformed report
        Files.writeString(reportDir.resolve("TEST-com.example.BrokenTest.xml"), "<testsuite><testcase");

        var result = BaselineTestResults.collect(tempDir, Instant.MIN);

        assertEquals(1, result.outcomes().size(), "valid report should be collected");
        assertNotNull(result.outcomes().get("com.example.PassingTest#validTest"));
        assertFalse(result.complete(), "collection must be marked incomplete when any report fails");
        assertEquals(1, result.failedReportCount(), "one malformed report");
    }

    @Test
    void includesModuleProvenanceInTestIdentityToPreventCollisions() throws Exception {
        // Two modules with the same test class and method but different outcomes
        writeReport(tempDir.resolve("module-a/target/surefire-reports"), "demo.OldTest", """
                <testsuite name="demo.OldTest" tests="1">
                  <testcase classname="demo.OldTest" name="oldA">
                    <failure message="failed in module-a"/>
                  </testcase>
                </testsuite>
                """);
        writeReport(tempDir.resolve("module-b/target/surefire-reports"), "demo.OldTest", """
                <testsuite name="demo.OldTest" tests="1">
                  <testcase classname="demo.OldTest" name="oldA"/>
                </testsuite>
                """);

        var result = BaselineTestResults.collect(tempDir, Instant.MIN);

        assertTrue(result.complete());
        Map<String, BaselineTestResults.Outcome> tests = result.outcomes();
        assertEquals(2, tests.size(), "both modules' tests should be preserved");
        // Module-a test failed
        assertEquals("failed", tests.get("module-a:demo.OldTest#oldA").status());
        assertEquals("failed in module-a", tests.get("module-a:demo.OldTest#oldA").reason());
        // Module-b test passed
        assertEquals("passed", tests.get("module-b:demo.OldTest#oldA").status());
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
