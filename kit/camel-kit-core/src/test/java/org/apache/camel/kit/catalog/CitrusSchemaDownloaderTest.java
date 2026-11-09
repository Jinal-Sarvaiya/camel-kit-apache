package org.apache.camel.kit.catalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CitrusSchemaDownloaderTest {

    private static final String VERSION = "5.0.1";
    private static final String PREFIX = "org/citrusframework/schema/citrus/" + VERSION + "/";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "/absolute", "..", "", "1/2", "1\\2"})
    void versionCannotSelectAnOutsideDestination(String version) throws Exception {
        Path cache = Files.createDirectory(tempDir.resolve("cache"));
        CitrusSchemaDownloader downloader
                = new CitrusSchemaDownloader(cache, new ArchiveClient(archive("schema.json")));
        assertThrows(IllegalArgumentException.class, () -> downloader.fetchCitrusSchemas(version, true));
        try (var paths = Files.list(cache)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void validArchiveGeneratesSchemasAndReferenceInsideCache() throws Exception {
        Path cache = tempDir.resolve("cache");
        fetch(cache, archive("index.json"));
        assertEquals("{}", Files.readString(cache.resolve("citrus/" + VERSION + "/index.json")));
        assertTrue(Files.isRegularFile(cache.resolve("citrus/" + VERSION + "/citrus-quick-reference.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../../outside", "/absolute"})
    void archivePathsCannotEscapeCache(String entry) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path outside = Files.writeString(tempDir.resolve("outside"), "untouched");
        String name = entry.startsWith("/") ? outside.toString() : entry;
        assertThrows(Exception.class, () -> fetch(cache, archive(name)));
        assertEquals("untouched", Files.readString(outside));
    }

    @ParameterizedTest
    @ValueSource(strings = {"citrus", "citrus/5.0.1", "citrus/5.0.1/nested"})
    void existingCacheDirectorySymlinksCannotRedirectExtraction(String relative) throws Exception {
        Path cache = Files.createDirectory(tempDir.resolve("cache"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "untouched");
        Path link = cache.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);

        assertThrows(Exception.class, () -> fetch(cache, archive("nested/schema.json")));

        assertEquals("untouched", Files.readString(sentinel));
        try (var paths = Files.list(outside)) {
            assertEquals(List.of(sentinel), paths.toList());
        }
        assertTrue(Files.isSymbolicLink(link));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema.json", "citrus-quick-reference.json", "citrus-quick-reference.md"})
    void schemaAndGeneratedReferenceFilesCannotOverwriteSymlinkTargets(String name) throws Exception {
        Path cache = Files.createDirectory(tempDir.resolve("cache"));
        Path version = Files.createDirectories(cache.resolve("citrus/" + VERSION));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        Path link = Files.createSymbolicLink(version.resolve(name), sentinel);

        assertThrows(Exception.class, () -> fetch(cache, archive("schema.json")));

        assertEquals("untouched", Files.readString(sentinel));
        assertTrue(Files.isSymbolicLink(link));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema.json", "citrus-quick-reference.json", "citrus-quick-reference.md"})
    void schemasAndReferencesReplaceHardLinksWithoutChangingOutsideContent(String name) throws Exception {
        Path cache = Files.createDirectory(tempDir.resolve("cache"));
        Path version = Files.createDirectories(cache.resolve("citrus/" + VERSION));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        Path generated = Files.createLink(version.resolve(name), sentinel);

        fetch(cache, archive("schema.json"));

        assertEquals("untouched", Files.readString(sentinel));
        assertTrue(Files.isRegularFile(generated));
        assertNotEquals("untouched", Files.readString(generated));
        assertFalse(Files.isSameFile(generated, sentinel), "Extraction and processing must replace the selected entry");
    }

    private void fetch(Path cache, byte[] jar) throws Exception {
        // Requires a package-private (Path, HttpClient) constructor; the public API remains unchanged.
        new CitrusSchemaDownloader(cache, new ArchiveClient(jar)).fetchCitrusSchemas(VERSION, true, message -> {
        });
    }

    private byte[] archive(String name) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(PREFIX + name));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static class ArchiveClient extends HttpClient {
        private final byte[] archive;

        ArchiveClient(byte[] archive) {
            this.archive = archive;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of(), (k, v) -> true);
                }

                @SuppressWarnings("unchecked")
                @Override
                public T body() {
                    return (T) new ByteArrayInputStream(archive);
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }
    }
}
