package org.apache.camel.kit.knowledge.mcp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.camel.kit.knowledge.embedding.OnnxEmbeddingProvider;
import org.apache.camel.kit.knowledge.embedding.OnnxReranker;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OfflineModelsTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"embedding", "reranker"})
    void bundledModelsInitializeOfflineInEitherOrder(String firstModel) throws Exception {
        runProbe(firstModel, "", "");
    }

    @ParameterizedTest
    @CsvSource({
            "embedding, DJL_OFFLINE, false",
            "reranker, DJL_OFFLINE, false",
            "embedding, RUST_FLAVOR, cu122",
            "reranker, RUST_FLAVOR, cu122"
    })
    void conflictingEnvironmentIsRejectedBeforeNativeInitialization(String firstModel, String name, String value)
            throws Exception {
        runProbe(firstModel, name, value);
    }

    @org.junit.jupiter.api.Test
    void explicitJnaPlatformPathIsPreserved() throws Exception {
        runProbe("embedding", "", "", tempDir.resolve("native-libraries").toString());
    }

    private void runProbe(String firstModel, String environmentName, String environmentValue) throws Exception {
        runProbe(firstModel, environmentName, environmentValue, "");
    }

    private void runProbe(String firstModel, String environmentName, String environmentValue, String jnaPath)
            throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Path temporary = Files.createDirectory(tempDir.resolve("tmp"));
        Path cache = tempDir.resolve("cache");
        Path output = tempDir.resolve("probe.log");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "-Duser.home=" + home,
                "-Djava.io.tmpdir=" + temporary,
                "-Donnx.threads=1",
                "-Dai.djl.offline=false",
                "-DRUST_FLAVOR=cu122",
                "-Dai.djl.util.cuda.fork=true",
                "-XX:ActiveProcessorCount=2",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), firstModel, environmentName, tempDir.toString(), jnaPath);
        var environment = builder.environment();
        for (String name : List.of("DJL_OFFLINE", "RUST_FLAVOR", "RUST_LIBRARY_PATH", "DJL_CACHE_DIR",
                "ENGINE_CACHE_DIR", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            environment.remove(name);
        }
        environment.put("DJL_CACHE_DIR", cache.toString());
        environment.put("ENGINE_CACHE_DIR", cache.toString());
        environment.put("TMPDIR", temporary.toString());
        environment.put("TMP", temporary.toString());
        environment.put("TEMP", temporary.toString());
        if (!environmentName.isEmpty()) {
            environment.put(environmentName, environmentValue);
        }
        Process child = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), () -> "Model probe timed out: " + readOutput(output));
            assertEquals(0, child.exitValue(), () -> readOutput(output));
            assertTrue(readOutput(output).contains("OFFLINE_MODELS_PASS"), () -> readOutput(output));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String readOutput(Path output) {
        try {
            return Files.readString(output);
        } catch (IOException e) {
            return e.toString();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (!args[3].isEmpty()) {
                System.setProperty("jna.platform.library.path", args[3]);
            }
            List<String> networkOpens = new ArrayList<>();
            installBlockingNetworkObserver(networkOpens);
            Path directory = Path.of(args[2]);
            Path recordingPath = directory.resolve("models.jfr");
            Throwable failure = null;
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ProcessStart").withThreshold(Duration.ZERO).withStackTrace();
                recording.enable("jdk.SocketRead").withThreshold(Duration.ZERO);
                recording.enable("jdk.SocketWrite").withThreshold(Duration.ZERO);
                recording.start();
                try {
                    if (args[1].isEmpty()) {
                        exercise(args[0]);
                        exercise(args[0].equals("embedding") ? "reranker" : "embedding");
                        assertEquals("true", System.getProperty("ai.djl.offline"));
                        assertEquals("cpu", System.getProperty("RUST_FLAVOR"));
                        assertEquals("false", System.getProperty("ai.djl.util.cuda.fork"));
                        assertEquals(args[3], System.getProperty("jna.platform.library.path"),
                                "An explicit JNA platform path must not be replaced");
                    } else {
                        IllegalStateException rejection
                                = assertThrows(IllegalStateException.class, () -> exercise(args[0]));
                        assertTrue(rejection.getMessage().contains(args[1]), rejection::getMessage);
                        assertFalse(Files.exists(directory.resolve("cache")),
                                "Rejection must precede native extraction");
                        try (var temporaryFiles = Files.list(directory.resolve("tmp"))) {
                            assertTrue(
                                    temporaryFiles.noneMatch(
                                            path -> path.getFileName().toString().startsWith("camel-kit-onnx-")),
                                    "Rejection must precede model extraction");
                        }
                    }
                } catch (Throwable e) {
                    failure = e;
                } finally {
                    recording.stop();
                    recording.dump(recordingPath);
                }
            }
            System.out.println("HTTP opens: " + networkOpens.size() + " " + networkOpens);
            assertTrue(networkOpens.isEmpty(),
                    () -> "Tokenizer initialization attempted network access: " + networkOpens);
            var events = RecordingFile.readAllEvents(recordingPath);
            var processes = events.stream().filter(event -> event.getEventType().getName().equals("jdk.ProcessStart"))
                    .toList();
            assertTrue(processes.isEmpty(), () -> "Model initialization started child processes: " + processes);
            assertFalse(events.stream().anyMatch(event -> event.getEventType().getName().startsWith("jdk.Socket")),
                    "Model initialization must not read or write sockets");
            if (failure != null) {
                throw new AssertionError("Model initialization contract failed", failure);
            }
            System.out.println("OFFLINE_MODELS_PASS");
        }

        private static void exercise(String model) {
            if (model.equals("embedding")) {
                float[] vector = new OnnxEmbeddingProvider().embed("Apache Camel routes messages between endpoints");
                assertEquals(OnnxEmbeddingProvider.DIMENSIONS, vector.length);
                double norm = 0;
                for (float value : vector) {
                    assertTrue(Float.isFinite(value));
                    norm += value * value;
                }
                assertEquals(1.0, norm, 0.0001, "Embedding must be a real normalized vector");
            } else {
                float score = new OnnxReranker().score("Apache Camel routes",
                        "Apache Camel routes messages between endpoints");
                assertTrue(Float.isFinite(score) && score > 0 && score < 1,
                        "Reranker must return a real relevance score");
            }
        }

        private static void installBlockingNetworkObserver(List<String> networkOpens) {
            URL.setURLStreamHandlerFactory(protocol -> {
                if (!protocol.equals("http") && !protocol.equals("https")) {
                    return null;
                }
                return new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) {
                        return blocked(url);
                    }

                    @Override
                    protected URLConnection openConnection(URL url, Proxy proxy) {
                        return blocked(url);
                    }

                    private URLConnection blocked(URL url) {
                        networkOpens.add(url.toString());
                        return new HttpURLConnection(url) {
                            @Override
                            public void disconnect() {
                            }

                            @Override
                            public boolean usingProxy() {
                                return false;
                            }

                            @Override
                            public void connect() throws IOException {
                                throw new IOException("Network disabled by offline-model test");
                            }

                            @Override
                            public int getResponseCode() {
                                return 404;
                            }

                            @Override
                            public String getResponseMessage() {
                                return "Network disabled by offline-model test";
                            }
                        };
                    }
                };
            });
        }
    }
}
