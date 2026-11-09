package org.apache.camel.kit.graph.parser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.kit.graph.ProjectGraph;

import com.sun.net.httpserver.HttpServer;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ParserSourceReadingContractTest {

    private static final String SECRET_PROPERTY = "camel.kit.source.contract.secret";
    private static final String SECRET_VALUE = "source-contract-runtime-secret";
    private static final String PLACEHOLDER = "${" + SECRET_PROPERTY + "}";
    private static final String PROJECT_CLASS = "source.contract.ProjectAction";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(ParserCase.class)
    // Public JFR APIs observe process and class loading on the supported JDKs (17, 21, and 25).
    @SuppressForbidden
    void parsingDoesNotRunProjectActionsOrResolveRuntimeValues(ParserCase parserCase) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path marker = tempDir.resolve("executed");
        String previous = System.setProperty(SECRET_PROPERTY, SECRET_VALUE);
        try (NetworkCanary network = new NetworkCanary()) {
            String actions = actions(marker, network.uri());
            String source = parserCase.source(actions, network.uri());
            Files.writeString(project.resolve(parserCase.fileName), source);
            ProjectGraph graph = new ProjectGraph();
            GraphParser parser = parserCase.parser();

            List<RecordedEvent> events = record(() -> parser.parse(project, graph));

            assertTrue(graph.hasNode(parserCase.expectedNode), "The fixture must actually be parsed");
            assertTrue(parser.warnings().isEmpty(), () -> parser.warnings().toString());
            assertFalse(graph.getNodes().toString().contains(SECRET_VALUE),
                    "Runtime system properties must not replace source placeholders");
            if (parserCase != ParserCase.DATAWEAVE) {
                assertTrue(graph.getNodes().toString().contains(PLACEHOLDER),
                        "The placeholder must remain literal in the graph");
            }
            assertFalse(Files.exists(marker), "Source code and compile-time annotations must not execute");
            assertEquals(0, network.requests.get(), "Source URLs must never be fetched");
            assertFalse(events.stream().anyMatch(event -> event.getEventType().getName().equals("jdk.ProcessStart")),
                    "Parsing must not start a child process");
            assertFalse(events.stream().anyMatch(event -> event.getEventType().getName().equals("jdk.ClassLoad")
                    && event.getClass("loadedClass").getName().equals(PROJECT_CLASS)),
                    "Parsing must not load a project class");
            assertEquals(source, Files.readString(project.resolve(parserCase.fileName)));
        } finally {
            if (previous == null) {
                System.clearProperty(SECRET_PROPERTY);
            } else {
                System.setProperty(SECRET_PROPERTY, previous);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ParserCase.class, names = {"POM", "XML", "MULE", "BIZTALK"})
    void xmlParsersDoNotFetchExternalEntities(ParserCase parserCase) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        try (NetworkCanary network = new NetworkCanary()) {
            String source = parserCase.source("", network.uri());
            String root = switch (parserCase) {
                case POM -> "project";
                case XML -> "routes";
                case MULE -> "mule";
                case BIZTALK -> "Document";
                default -> throw new IllegalArgumentException();
            };
            String entitySource = parserCase == ParserCase.XML || parserCase == ParserCase.MULE
                    ? source.replace("<![CDATA[]]>", "&external;")
                    : source.replace(PLACEHOLDER, "&external;");
            Files.writeString(project.resolve(parserCase.fileName),
                    "<!DOCTYPE " + root + " [<!ENTITY external SYSTEM \"" + network.uri() + "\">]>\n"
                                                                    + entitySource);
            ProjectGraph graph = new ProjectGraph();
            try {
                parserCase.parser().parse(project, graph);
            } catch (RuntimeException expectedRejection) {
                // POM rejects an unresolved entity; the XML families report parser warnings instead.
            }
            assertEquals(0, network.requests.get());
            assertFalse(graph.getNodes().values().stream()
                    .flatMap(node -> node.properties().values().stream())
                    .anyMatch(value -> value.contains("entity-body")));
        }
    }

    @Test
    // The positive control reads the same public JFR events as the parser checks.
    @SuppressForbidden
    void sideEffectObserversDetectRealNetworkProcessAndClassLoading() throws Exception {
        // Test discovery may preload nested classes; a fresh loader makes this control independent of discovery order.
        try (NetworkCanary network = new NetworkCanary();
             URLClassLoader loader = new URLClassLoader(
                     new URL[]{getClass().getProtectionDomain().getCodeSource().getLocation()},
                     ClassLoader.getPlatformClassLoader())) {
            List<RecordedEvent> events = record(() -> {
                try (var input = network.uri().toURL().openStream()) {
                    assertEquals("entity-body",
                            new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                Process child = new ProcessBuilder(javaExecutable(), "-version")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
                Class.forName(getClass().getName() + "$ClassLoadingCanary", true, loader);
            });
            assertEquals(1, network.requests.get());
            assertTrue(events.stream().anyMatch(event -> event.getEventType().getName().equals("jdk.ProcessStart")));
            assertTrue(events.stream().anyMatch(event -> event.getEventType().getName().equals("jdk.ClassLoad")
                    && event.getClass("loadedClass").getName().endsWith("$ClassLoadingCanary")));
        }
    }

    // Public JFR recording APIs are available on all supported JDKs.
    @SuppressForbidden
    private List<RecordedEvent> record(CheckedAction action) throws Exception {
        Path recordingPath = tempDir.resolve("source-reading.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ProcessStart").withThreshold(Duration.ZERO);
            recording.enable("jdk.ClassLoad").withThreshold(Duration.ZERO);
            recording.start();
            action.run();
            recording.stop();
            recording.dump(recordingPath);
        }
        return RecordingFile.readAllEvents(recordingPath);
    }

    private static String actions(Path marker, URI uri) {
        return "java.nio.file.Files.writeString(java.nio.file.Path.of(\"" + quote(marker.toString())
               + "\"), \"executed\"); new java.net.URL(\"" + uri
               + "\").openStream().close(); new ProcessBuilder(\"" + quote(javaExecutable())
               + "\", \"-version\").start().waitFor();";
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private enum ParserCase {
        POM("pom.xml", "maven:contract:source"),
        JAVA("ProjectAction.java", "class:" + PROJECT_CLASS),
        GROOVY("ProjectAction.groovy", "class:" + PROJECT_CLASS),
        XML("routes.xml", "route:contract"),
        MULE("flow.xml", "mule-flow:contract"),
        DATAWEAVE("transform.dwl", "dataweave:transform.dwl"),
        BIZTALK("contract.btp", "biztalk-pipeline:" + PLACEHOLDER),
        YAML("routes.yaml", "route:contract"),
        CONFIG("application.properties", "config:contract.secret");

        private final String fileName;
        private final String expectedNode;

        ParserCase(String fileName, String expectedNode) {
            this.fileName = fileName;
            this.expectedNode = expectedNode;
        }

        GraphParser parser() {
            return switch (this) {
                case POM -> new PomParser();
                case JAVA -> new JavaGraphParser();
                case GROOVY -> new GroovyGraphParser();
                case XML -> new XmlRouteParser();
                case MULE -> new MuleXmlFlowParser();
                case DATAWEAVE -> new DataWeaveParser();
                case BIZTALK -> new BizTalkParser();
                case YAML -> new YamlRouteParser();
                case CONFIG -> new ConfigParser();
            };
        }

        String source(String actions, URI uri) {
            return switch (this) {
                case POM ->
                    String.format(Locale.ROOT,
                            """
                                    <project><modelVersion>4.0.0</modelVersion><groupId>contract</groupId>
                                    <artifactId>source</artifactId><version>%s</version>
                                    <repositories><repository><id>canary</id><url>%s</url></repository></repositories>
                                    <build><plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId>
                                    <configuration><executable>java</executable><arguments><argument>-version</argument></arguments></configuration>
                                    <executions><execution><phase>validate</phase><goals><goal>exec</goal></goals></execution></executions>
                                    </plugin></plugins></build></project>
                                    """,
                            PLACEHOLDER, uri);
                case JAVA -> String.format(Locale.ROOT, """
                        package source.contract;
                        public class ProjectAction {
                            static { try { %s } catch (Exception e) { throw new RuntimeException(e); } }
                            public void configure() { from("direct:%s").to("%s"); }
                        }
                        """, actions, PLACEHOLDER, uri);
                case GROOVY -> String.format(Locale.ROOT, """
                        package source.contract
                        @groovy.transform.ASTTest(phase=org.codehaus.groovy.control.CompilePhase.SEMANTIC_ANALYSIS,
                            value={ %s })
                        class ProjectAction {
                            static { %s }
                            void configure() { from('direct:%s').to('%s') }
                        }
                        """, actions, actions, PLACEHOLDER, uri);
                case XML -> String.format(Locale.ROOT, """
                        <routes xmlns="http://camel.apache.org/schema/spring">
                          <route id="contract"><from uri="direct:%s"/><to uri="%s"/>
                            <setBody><groovy><![CDATA[%s]]></groovy></setBody><bean ref="%s"/>
                          </route>
                        </routes>
                        """, PLACEHOLDER, uri, actions, PROJECT_CLASS);
                case MULE ->
                    String.format(Locale.ROOT,
                            """
                                    <mule xmlns="http://www.mulesoft.org/schema/mule/core"
                                          xmlns:http="http://www.mulesoft.org/schema/mule/http"
                                          xmlns:scripting="http://www.mulesoft.org/schema/mule/scripting">
                                      <http:request-config name="%s"/>
                                      <flow name="contract"><http:request config-ref="%s" url="%s"/>
                                        <scripting:execute engine="groovy"><scripting:code><![CDATA[%s]]></scripting:code></scripting:execute>
                                      </flow>
                                    </mule>
                                    """,
                            PLACEHOLDER, PLACEHOLDER, uri, actions);
                case DATAWEAVE -> String.format(Locale.ROOT, """
                        %%dw 2.0
                        output application/json
                        var remote = readUrl("%s", "text/plain")
                        ---
                        { secret: p("%s"), data: remote, value: payload.order.id }
                        """, uri, SECRET_PROPERTY);
                case BIZTALK -> String.format(Locale.ROOT, """
                        <Document PolicyFilePath="%s"><FriendlyName>%s</FriendlyName>
                          <Stages><Stage><Components><Component Name="%s" ComponentName="canary" Version="1.0"
                            Description="%s"/></Components></Stage></Stages>
                        </Document>
                        """, uri, PLACEHOLDER, PROJECT_CLASS, uri);
                case YAML -> String.format(Locale.ROOT, """
                        - route: !!source.contract.ProjectAction
                            id: contract
                            from:
                              uri: 'direct:%s'
                              steps:
                                - to: '%s'
                                - groovy: '%s'
                                - bean:
                                    ref: '%s'
                        """, PLACEHOLDER, uri, actions, PROJECT_CLASS);
                case CONFIG -> String.format(Locale.ROOT, """
                        contract.secret=%s
                        camel.component.http.address=%s
                        camel.beans.action=#class:%s
                        contract.action=%s
                        """, PLACEHOLDER, uri, PROJECT_CLASS, actions);
            };
        }
    }

    // The public jdk.httpserver module supplies a loopback-only network observer on the supported JDKs.
    @SuppressForbidden
    private static final class NetworkCanary implements AutoCloseable {
        private final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;

        NetworkCanary() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] body = "entity-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/canary");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static class ClassLoadingCanary {
    }
}
