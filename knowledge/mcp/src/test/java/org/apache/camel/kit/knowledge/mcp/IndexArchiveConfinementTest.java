package org.apache.camel.kit.knowledge.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class IndexArchiveConfinementTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {
            "knowledge-index/../../outside", "knowledge-index//absolute",
            "/absolute", "knowledge-index/..\\..\\outside", "knowledge-index/C:\\outside"})
    void archiveNamesCannotOverwriteOutsideFiles(String entry) throws Exception {
        Path outside = Files.writeString(tempDir.resolve("outside"), "untouched");
        String name = entry.equals("/absolute") ? outside.toString()
                : entry.equals("knowledge-index//absolute") ? "knowledge-index/" + outside : entry;
        Path manifest = publish(name, "hostile", false);
        IndexDownloader downloader = downloader(manifest);

        // Rejection or omission of unsafe entries are both acceptable confinement outcomes.
        try {
            Path index = downloader.resolve().dir();
            assertEquals("index", Files.readString(index.resolve("segments_1")));
        } catch (IOException expected) {
            assertFalse(Files.exists(tempDir.resolve("cache/current")));
        }
        assertEquals("untouched", Files.readString(outside));
    }

    @Test
    void unixSymlinkEntryCannotCreateALinkUsedByTheFollowingEntry() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "untouched");
        Path manifest = publish("knowledge-index/link", outside.toString(), true);

        // java.util.zip has no symlink extraction: a link entry must never become a filesystem symlink.
        try {
            Path index = downloader(manifest).resolve().dir();
            assertFalse(Files.isSymbolicLink(index.resolve("link")));
        } catch (IOException expected) {
            assertFalse(Files.exists(tempDir.resolve("cache/current")));
        }
        assertEquals("untouched", Files.readString(sentinel));
        assertFalse(Files.exists(outside.resolve("escape")));
    }

    private Path publish(String name, String content, boolean symlink) throws Exception {
        Path publish = Files.createDirectory(tempDir.resolve("publish"));
        Path archive = publish.resolve("index.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "knowledge-index/segments_1", "index");
            write(zip, name, content);
            if (symlink) {
                write(zip, "knowledge-index/link/escape", "hostile");
            }
        }
        if (symlink) {
            byte[] bytes = Files.readAllBytes(archive);
            byte[] filename = name.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i + 46 + filename.length <= bytes.length; i++) {
                if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2
                        && java.util.Arrays.equals(filename,
                                java.util.Arrays.copyOfRange(bytes, i + 46, i + 46 + filename.length))) {
                    bytes[i + 5] = 3; // Unix creator: external attributes contain the POSIX mode.
                    int attributes = 0120777 << 16;
                    for (int j = 0; j < 4; j++) {
                        bytes[i + 38 + j] = (byte) (attributes >>> (8 * j));
                    }
                }
            }
            Files.write(archive, bytes);
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        return Files.writeString(publish.resolve("index.json"), new ObjectMapper().createObjectNode()
                .put("version", "v1").put("sha256", sha).put("asset", "index.zip")
                .put("embeddingModel", "granite-embedding-small-english-r2-q8").toString());
    }

    private void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private IndexDownloader downloader(Path manifest) {
        return new IndexDownloader(new IndexResolverConfig() {
            @Override
            public Optional<String> path() {
                return Optional.empty();
            }

            @Override
            public Optional<String> url() {
                return Optional.of(manifest.toUri().toString());
            }

            @Override
            public String releasesUrl() {
                return "http://unused.invalid/releases";
            }

            @Override
            public Optional<String> version() {
                return Optional.empty();
            }

            @Override
            public String cacheDir() {
                return tempDir.resolve("cache").toString();
            }
        });
    }
}
