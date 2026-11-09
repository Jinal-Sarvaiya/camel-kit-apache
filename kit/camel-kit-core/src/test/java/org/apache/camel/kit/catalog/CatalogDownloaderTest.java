package org.apache.camel.kit.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CatalogDownloaderTest {

    private static final Set<String> LTS_VERSIONS = Set.of("4.14", "4.18", "4.21");

    @TempDir
    Path tempDir;

    @Test
    void fallbackVersionComesFromConstructor() {
        CatalogDownloader downloader = new CatalogDownloader(tempDir, "4.14.7", LTS_VERSIONS);
        assertEquals("4.14.7", downloader.fallbackVersion());
    }

    @Test
    void rejectsNullCacheDir() {
        assertThrows(NullPointerException.class, () -> new CatalogDownloader(null, "4.14.7", LTS_VERSIONS));
    }

    @Test
    void rejectsNullFallbackVersion() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogDownloader(tempDir, null, LTS_VERSIONS));
    }

    @Test
    void rejectsBlankFallbackVersion() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogDownloader(tempDir, "  ", LTS_VERSIONS));
    }

    @Test
    void rejectsNullLtsVersions() {
        assertThrows(NullPointerException.class, () -> new CatalogDownloader(tempDir, "4.21.0", null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../outside", "4.0/../../outside", "4.0\\outside", " 4.0", "4.0?query"})
    void rejectsUnsafeVersionsBeforeReadingOrFetchingCatalogs(String version) {
        CatalogDownloader downloader = new CatalogDownloader(tempDir, "4.14.7", LTS_VERSIONS);
        for (boolean refresh : new boolean[]{false, true}) {
            assertThrows(IllegalArgumentException.class, () -> downloader.fetchComponentCatalog(version, refresh));
            assertThrows(IllegalArgumentException.class, () -> downloader.fetchKameletCatalog(version, refresh));
            assertThrows(IllegalArgumentException.class, () -> downloader.fetchYamlSchema(version, refresh));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.22.1", "4.23.0-SNAPSHOT", "4.23.0-RC1"})
    void safeVersionsStillReadCachedCatalogs(String version) throws Exception {
        CatalogDownloader downloader = new CatalogDownloader(tempDir, "4.14.7", LTS_VERSIONS);
        Files.writeString(tempDir.resolve("components-" + version + ".json"), "{\"cached\":true}");
        Files.writeString(tempDir.resolve("kamelets-" + version.replace(".", "_") + ".json"), "{\"cached\":true}");
        Files.writeString(tempDir.resolve("camelYamlDsl-" + version + ".json"), "{}");
        assertTrue(downloader.fetchComponentCatalog(version, false).path("cached").asBoolean());
        assertTrue(downloader.fetchKameletCatalog(version, false).path("cached").asBoolean());
        assertDoesNotThrow(() -> downloader.fetchYamlSchema(version, false));
    }

    @Test
    void searchComponentsOnEmptyCatalog() {
        CatalogDownloader downloader = new CatalogDownloader(tempDir, "4.14.7", LTS_VERSIONS);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var catalog = mapper.createObjectNode();
        catalog.putArray("components");
        var results = downloader.searchComponents("kafka", catalog, 10);
        assertTrue(results.isEmpty());
    }
}
