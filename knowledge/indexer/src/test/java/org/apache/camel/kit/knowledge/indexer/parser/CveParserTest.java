package org.apache.camel.kit.knowledge.indexer.parser;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CveParserTest {

    @Test
    void parsesFrontmatter() throws Exception {
        String content = new String(
                getClass().getClassLoader().getResourceAsStream("test-cve.md").readAllBytes(),
                StandardCharsets.UTF_8);

        CveParser.CveAdvisory cve = CveParser.parse(content);

        assertEquals("CVE-2024-22369", cve.cveId());
        assertEquals("HIGH", cve.severity());
        assertNotNull(cve.summary());
        assertTrue(cve.summary().contains("Unsafe Deserialization"));
        assertNotNull(cve.description());
        assertNotNull(cve.affected());
        assertEquals(4, cve.fixedVersions().size());
        assertTrue(cve.fixedVersions().contains("4.0.4"));
        assertTrue(cve.fixedVersions().contains("3.21.4"));
        assertTrue(cve.jiraIds().contains("CAMEL-20303"));
        assertEquals("2024-02-19", cve.publishedDate());
    }

    @Test
    void extractsComponentFromDescription() {
        String desc = "Unsafe Deserialization from JDBCAggregationRepository in camel-sql";
        String component = CveParser.extractComponent(desc);
        assertEquals("sql", component);
    }

    @Test
    void extractsComponentFromSummary() {
        String desc = "The Camel CXF component allows XXE attacks";
        String component = CveParser.extractComponent(desc);
        assertEquals("cxf", component);
    }

    @Test
    void parsesAffectedVersionRanges() {
        String affected = "From 3.0.0 before 3.21.4, from 4.0.0 before 4.0.4";
        var ranges = CveParser.parseAffectedVersions(affected);
        assertEquals(2, ranges.size());
        assertTrue(ranges.contains("3.0.0-3.21.4"));
        assertTrue(ranges.contains("4.0.0-4.0.4"));
    }

    /** Flips a flag if SnakeYAML ever constructs it; a SafeConstructor-backed parser must not. */
    public static final class Canary {
        static volatile boolean constructed = false;

        public Canary() {
            constructed = true;
        }
    }

    @Test
    void cveGlobalTagFallsBackToLineExtractionWithoutConstructing() {
        Canary.constructed = false;
        String content = "---\n"
                         + "cve: CVE-2099-0001\n"
                         + "severity: HIGH\n"
                         + "summary: \"Canary advisory\"\n"
                         + "pwn: !!" + Canary.class.getName() + " {}\n"
                         + "fixed: \"4.0.4, 3.21.4\"\n"
                         + "---\n"
                         + "Body referencing CAMEL-12345.\n";

        // The global tag makes SafeConstructor throw; parseYamlFrontmatter catches it and falls
        // back to line-based regex extraction, so the advisory still parses and nothing is constructed.
        CveParser.CveAdvisory cve = CveParser.parse(content);

        assertFalse(Canary.constructed,
                "SafeConstructor must not instantiate a globally tagged type in CVE frontmatter");
        assertEquals("CVE-2099-0001", cve.cveId());
        assertEquals("HIGH", cve.severity());
        assertTrue(cve.fixedVersions().contains("4.0.4"));
    }
}
