package org.apache.camel.kit.knowledge.mcp;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.kit.knowledge.schema.KnowledgeDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the MCP entry points against a real, deterministic BM25 index without model initialization. */
class KnowledgeInputBoundaryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Directory directory;
    private LuceneSearchService service;
    private KnowledgeMcpServer server;

    @TempDir
    Path temp;

    @BeforeEach
    void createIndex() throws Exception {
        directory = new ByteBuffersDirectory();
        try (StandardAnalyzer analyzer = new StandardAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (int i = 0; i < 40; i++) {
                writer.addDocument(new KnowledgeDocument("release-" + i, "apache_camel")
                        .source("apache-camel").docType("release-notes").sourceVersion("4.22")
                        .component("safecomponent").jiraId("CAMEL-999")
                        .sectionTitle("Release notes").content("needle release ordinary documentation").build());
                writer.addDocument(new KnowledgeDocument("advisory-" + i, "apache_camel")
                        .source("apache-camel").docType("cve").sourceVersion("4.22")
                        .cveId("CVE-2099-0001").erratumId("advisory-" + i).severity("HIGH")
                        .sectionTitle("Security advisory").content("CVE security vulnerability advisorytoken").build());
            }
            writer.addDocument(new KnowledgeDocument("secretmarker", "apache_camel")
                    .docType("guide").sectionTitle("Decoy").content("unrelated orchard").build());
            writer.addDocument(new KnowledgeDocument("foreign-domain", "another_domain")
                    .docType("guide").sectionTitle("Other domain").content("needle release").build());
        }
        service = new LuceneSearchService();
        DirectoryReader reader = DirectoryReader.open(directory);
        // Install the real Lucene fixture without invoking unrelated ONNX/native startup.
        setField(service, "reader", reader);
        setField(service, "searcher", new IndexSearcher(reader));
        server = new KnowledgeMcpServer();
        server.searchService = service;
    }

    @AfterEach
    void closeIndex() throws Exception {
        if (service != null) {
            service.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    @Test
    void querySyntaxCannotSelectFieldsOrTurnIntoMatchAll() throws Exception {
        assertEquals(0, results(server.camel_docs_search("id:secretmarker", null, 25)).size());
        assertEquals(0, results(server.camel_docs_search("*:*", null, 25)).size());

        for (String query : List.of("needle", "needle OR *:*", "(\"needle")) {
            JsonNode matches = results(server.camel_docs_search(query, null, 25));
            assertEquals(25, matches.size(), query);
            for (JsonNode match : matches) {
                assertTrue(match.path("id").asText().startsWith("release-"), match.toString());
            }
        }
        assertTrue(service.searchErrata(null, null, null, "*:*", 25).isEmpty());
        assertEquals(25, service.searchErrata(null, null, null, "advisorytoken", 25).size());
    }

    @Test
    void identifiersAndVersionFiltersCannotBecomeQueries() throws Exception {
        assertEquals(20, results(server.camel_docs_jira_lookup("CAMEL-999")).size());
        assertEquals(0, results(server.camel_docs_jira_lookup("CAMEL-999 OR *:*")).size());
        assertEquals(20, results(server.camel_docs_cve_search("CVE-2099-0001", null, null, null, 100)).size());
        assertEquals(0, results(server.camel_docs_cve_search("CVE-2099-0001 OR *:*", null, null, null, 100)).size());
        assertEquals(10, results(server.camel_docs_component_info("safecomponent", null, null)).size());
        assertEquals(0, results(server.camel_docs_component_info("id:secretmarker", null, null)).size());
        assertEquals(0, results(server.camel_docs_search("needle", "absent OR *:*", 25)).size());
        assertTrue(service.lookupComponent("apache_camel", "safecomponent OR *:*", null, null).isEmpty());
    }

    @Test
    void everyConfigurableResultLimitIsBoundedAtTheToolEntryPoint() throws Exception {
        assertEquals(25, results(server.camel_docs_search("needle", null, Integer.MAX_VALUE)).size());
        assertEquals(25, results(server.camel_docs_release_info("4.22", Integer.MAX_VALUE)).size());
        assertEquals(25, results(server.camel_docs_cve_search(null, null, null, null, Integer.MAX_VALUE)).size());
        assertEquals(1, results(server.camel_docs_search("needle", null, -1)).size());
        assertEquals(1, results(server.camel_docs_release_info("4.22", 0)).size());
        assertEquals(1, results(server.camel_docs_cve_search(null, null, null, null, -1)).size());
        assertEquals(5, results(server.camel_docs_search("needle", null, null)).size());
        assertEquals(20, results(server.camel_docs_release_info("4.22", null)).size());
        assertEquals(10, results(server.camel_docs_cve_search(null, null, null, null, null)).size());
    }

    @Test
    void conventionalSeveritySpellingsReturnTheSameAdvisories() throws Exception {
        for (String severity : List.of("hIgH", "HIGH", "high", "High")) {
            JsonNode matches = results(server.camel_docs_cve_search(null, null, severity, null, 25));
            assertEquals(25, matches.size(), severity);
            for (JsonNode match : matches) {
                assertEquals("HIGH", match.path("severity").asText(), match.toString());
            }
        }
    }

    @Test
    void endpointValidationDoesNotContactAnHttpEndpoint() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer canary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        canary.createContext("/canary", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        canary.start();
        try {
            String uri = "http://127.0.0.1:" + canary.getAddress().getPort() + "/canary";
            JsonNode validation = mapper.readTree(server.camel_docs_validate_endpoint(uri));
            assertTrue(validation.path("valid").asBoolean(), validation.toString());
            assertEquals(uri, validation.path("uri").asText());
            JsonNode stylesheet = mapper.readTree(server.camel_docs_validate_endpoint("xslt:" + uri));
            assertTrue(stylesheet.path("valid").asBoolean(), stylesheet.toString());
            assertEquals(0, requests.get(), "Catalog validation must not send the endpoint a request");
        } finally {
            canary.stop(0);
        }
    }

    @Test
    void endpointValidationDoesNotRunCommandsOrModifyFiles() throws Exception {
        Path marker = temp.resolve("executed");
        Path command = temp.resolve("BoundaryCanary.java");
        Files.writeString(command, "class BoundaryCanary { public static void main(String[] args) throws Exception {"
                                   + "java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), \"executed\"); }}");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        JsonNode exec = mapper.readTree(server.camel_docs_validate_endpoint(
                "exec:" + executable + "?args=" + command + " " + marker));
        assertTrue(exec.path("valid").asBoolean(), exec.toString());
        assertFalse(Files.exists(marker), "The exec endpoint is metadata, not a command invocation");

        Path secret = temp.resolve("private.xml");
        Files.writeString(secret, "<private>content-canary-unique</private>");
        String validation = server.camel_docs_validate_endpoint("xslt:" + secret.toUri());
        assertTrue(mapper.readTree(validation).path("valid").asBoolean(), validation);
        assertFalse(validation.contains("content-canary-unique"));
        assertEquals("<private>content-canary-unique</private>", Files.readString(secret));

        Path output = temp.resolve("output");
        JsonNode file = mapper.readTree(server.camel_docs_validate_endpoint("file:" + output + "?fileName=result.txt"));
        assertTrue(file.path("valid").asBoolean(), file.toString());
        assertFalse(Files.exists(output), "Catalog validation must not create the endpoint directory");
    }

    private JsonNode results(String response) throws Exception {
        JsonNode json = mapper.readTree(response);
        assertFalse(json.has("error"), response);
        assertTrue(json.path("results").isArray(), response);
        return json.path("results");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
