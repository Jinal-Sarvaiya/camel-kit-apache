package org.apache.camel.kit.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the recorded test baseline ({@code .camel-kit/baseline-test-results.json}) from Surefire/Failsafe XML reports.
 * <p>
 * The baseline stores the outcome of every individual test, keyed by {@code class#method}, and the source revision the
 * results belong to. Validation compares per-test identities, so a test that now fails is a regression even when the
 * number of failures is unchanged, and a pre-existing failure is only ever recognised by name.
 */
final class BaselineTestResults {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private BaselineTestResults() {
    }

    /**
     * One test outcome. {@code status} is {@code passed}, {@code failed}, {@code error} or {@code skipped}.
     */
    record Outcome(String status, String reason) {
    }

    /**
     * Collection result carrying both the outcomes and whether the collection was complete. If any report could not be
     * parsed or read, {@code complete} is false and the caller must not treat the baseline as usable preservation
     * evidence.
     */
    record CollectionResult(Map<String, Outcome> outcomes, boolean complete, int failedReportCount) {
    }

    /**
     * Collects every test outcome from the {@code TEST-*.xml} reports below {@code projectRoot}'s
     * {@code target/surefire-reports} and {@code target/failsafe-reports} directories, including those of Maven
     * modules. Only reports modified after {@code afterTimestamp} are included, so stale reports from a previous
     * invocation are rejected. A report that cannot be parsed is skipped; the result then simply lacks those tests and
     * the caller records that the evidence is incomplete.
     *
     * @param afterTimestamp only reports modified after this instant are collected; use the instant immediately before
     *                       starting the test process to ensure only reports from the current invocation are included
     */
    static CollectionResult collect(Path projectRoot, Instant afterTimestamp) throws IOException {
        Map<String, Outcome> outcomes = new TreeMap<>();
        List<Path> reports = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .filter(p -> {
                        String normalized = p.toString().replace('\\', '/');
                        return normalized.contains("/target/surefire-reports/")
                                || normalized.contains("/target/failsafe-reports/");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isAfter(afterTimestamp);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted()
                    .forEach(reports::add);
        }
        int failedReports = 0;
        for (Path report : reports) {
            try {
                readReport(projectRoot, report, outcomes);
            } catch (Exception e) {
                failedReports++;
            }
        }
        boolean complete = failedReports == 0;
        return new CollectionResult(outcomes, complete, failedReports);
    }

    private static void readReport(Path projectRoot, Path report, Map<String, Outcome> outcomes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(report.toFile());

        // Extract module path from report path: e.g. "module-a/target/surefire-reports/TEST-X.xml" → "module-a"
        // Root module reports are just "target/surefire-reports/TEST-X.xml" → "" (empty module)
        String relativeReportPath = projectRoot.relativize(report).toString().replace('\\', '/');
        String modulePrefix = "";
        if (relativeReportPath.contains("/target/")) {
            String modulePath = relativeReportPath.substring(0, relativeReportPath.indexOf("/target/"));
            if (!modulePath.isEmpty()) {
                modulePrefix = modulePath + ":";
            }
        }

        NodeList cases = document.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);
            String className = testCase.getAttribute("classname");
            String name = testCase.getAttribute("name");
            if (name.isEmpty()) {
                continue;
            }
            String baseId = className.isEmpty() ? name : className + "#" + name;
            String id = modulePrefix + baseId;

            // Detect collisions: if the same test identity appears twice, fail the collection.
            // This can only happen if reports are malformed or there's a bug in identity construction.
            if (outcomes.containsKey(id)) {
                throw new IllegalStateException(
                        "Duplicate test identity '" + id + "' in " + report + "; baseline collection cannot continue");
            }
            outcomes.put(id, outcomeOf(testCase));
        }
    }

    private static Outcome outcomeOf(Element testCase) {
        for (String tag : List.of("failure", "error")) {
            NodeList found = testCase.getElementsByTagName(tag);
            if (found.getLength() > 0) {
                Element element = (Element) found.item(0);
                String message = element.getAttribute("message");
                return new Outcome(
                        "failure".equals(tag) ? "failed" : "error",
                        message.isEmpty() ? element.getAttribute("type") : message);
            }
        }
        if (testCase.getElementsByTagName("skipped").getLength() > 0) {
            return new Outcome("skipped", "");
        }
        return new Outcome("passed", "");
    }

    /**
     * Serialises a baseline file.
     *
     * @param status   {@code passed}, {@code failed}, {@code timeout} or {@code not_run}
     * @param reason   why, for a human or the validation report
     * @param revision the source revision the results were recorded against (the Git HEAD, or {@code unknown})
     * @param dirty    whether the working tree had uncommitted changes when the tests ran
     * @param complete whether all test reports were successfully parsed; false if any were malformed or unreadable
     * @param tests    per-test outcomes; empty when none were recorded
     */
    static String toJson(
            String status, String reason, String revision, boolean dirty, boolean complete,
            Map<String, Outcome> tests)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", status);
        root.put("reason", reason);
        root.put("timestamp", Instant.now().toString());
        root.put("revision", revision);
        root.put("dirty", dirty);
        root.put("complete", complete);
        root.put("testCount", tests.size());
        ObjectNode testNode = root.putObject("tests");
        tests.forEach((id, outcome) -> {
            ObjectNode entry = testNode.putObject(id);
            entry.put("status", outcome.status());
            if (!outcome.reason().isEmpty()) {
                entry.put("reason", outcome.reason());
            }
        });
        ArrayNode summary = root.putArray("nonPassing");
        tests.forEach((id, outcome) -> {
            if (!"passed".equals(outcome.status()) && !"skipped".equals(outcome.status())) {
                summary.add(id);
            }
        });
        return new String(MAPPER.writeValueAsBytes(root), StandardCharsets.UTF_8);
    }
}
