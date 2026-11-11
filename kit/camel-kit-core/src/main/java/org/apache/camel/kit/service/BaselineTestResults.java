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
     * Collects every test outcome from the {@code TEST-*.xml} reports below {@code projectRoot}'s
     * {@code target/surefire-reports} and {@code target/failsafe-reports} directories, including those of Maven
     * modules. A report that cannot be parsed is skipped; the result then simply lacks those tests and the caller
     * records that the evidence is incomplete.
     */
    static Map<String, Outcome> collect(Path projectRoot) throws IOException {
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
                    .sorted()
                    .forEach(reports::add);
        }
        for (Path report : reports) {
            try {
                readReport(report, outcomes);
            } catch (Exception e) {
                // An unreadable report is reported by the caller as incomplete evidence.
            }
        }
        return outcomes;
    }

    private static void readReport(Path report, Map<String, Outcome> outcomes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(report.toFile());
        NodeList cases = document.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);
            String className = testCase.getAttribute("classname");
            String name = testCase.getAttribute("name");
            if (name.isEmpty()) {
                continue;
            }
            String id = className.isEmpty() ? name : className + "#" + name;
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
     * @param tests    per-test outcomes; empty when none were recorded
     */
    static String toJson(String status, String reason, String revision, boolean dirty, Map<String, Outcome> tests)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", status);
        root.put("reason", reason);
        root.put("timestamp", Instant.now().toString());
        root.put("revision", revision);
        root.put("dirty", dirty);
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
