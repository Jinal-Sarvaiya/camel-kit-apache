package org.apache.camel.kit.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ForageCatalogConfinementTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {".cache", ".cache/forage"})
    void cacheParentsCannotRedirectExtraction(String relative) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "untouched");
        Path link = project.resolve(relative);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);

        assertThrows(IOException.class,
                () -> new ForageCatalogService().cacheFromJar(new ByteArrayInputStream(archive()), project, "1.5.0"));

        assertEquals("untouched", Files.readString(sentinel));
        try (var paths = Files.list(outside)) {
            assertEquals(List.of(sentinel), paths.toList());
        }
        assertTrue(Files.isSymbolicLink(link));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../../outside", "/absolute"})
    void versionCannotSelectAnOutsideDestination(String version) throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "untouched");
        Files.createDirectories(project.resolve(".cache/forage"));
        String input = version.startsWith("/") ? outside.toString() : version;

        assertThrows(IllegalArgumentException.class,
                () -> new ForageCatalogService().cacheFromJar(new ByteArrayInputStream(archive()), project, input));

        assertEquals("untouched", Files.readString(sentinel));
        try (var paths = Files.list(outside)) {
            assertEquals(List.of(sentinel), paths.toList());
        }
    }

    @Test
    void archiveEntryNamesCannotEscapeTheExactCatalogAllowlist() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path sentinel = Files.writeString(tempDir.resolve("outside"), "untouched");
        byte[] bytes = archive("catalog/../../../../../outside", sentinel.toString(),
                "catalog/..\\..\\outside", "catalog/forage-catalog.json/../../outside");

        Path cache = new ForageCatalogService().cacheFromJar(new ByteArrayInputStream(bytes), project, "1.5.0");

        assertEquals("untouched", Files.readString(sentinel));
        assertEquals("{}", Files.readString(cache.resolve("forage-catalog.json")));
        assertEquals("{}", Files.readString(cache.resolve("forage-configuration-catalog.json")));
        try (var paths = Files.list(cache)) {
            assertEquals(2, paths.count());
        }
    }

    private byte[] archive(String... hostileNames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : hostileNames) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("hostile".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (String name : List.of("catalog/forage-catalog.json", "catalog/forage-configuration-catalog.json")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("{}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
