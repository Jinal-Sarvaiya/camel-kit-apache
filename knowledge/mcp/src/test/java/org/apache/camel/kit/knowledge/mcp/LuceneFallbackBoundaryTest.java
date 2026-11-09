package org.apache.camel.kit.knowledge.mcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.kit.knowledge.schema.KnowledgeDocument;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class LuceneFallbackBoundaryTest {

    @TempDir
    Path temp;

    @Test
    void pinnedResolutionFailureNeverUsesAnAvailableClasspathIndex() throws Exception {
        AtomicInteger resourceReads = new AtomicInteger();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        LuceneSearchService service = new LuceneSearchService();
        IOException failure = new IOException("Pinned index is unavailable");
        service.indexDownloader = failingDownloader(true, failure);
        Thread.currentThread().setContextClassLoader(classpathIndex(resourceReads));
        try {
            RuntimeException thrown = assertThrows(RuntimeException.class, service::init);
            assertSame(failure, thrown.getCause());
            assertEquals(0, resourceReads.get(), "A pin must not silently select even a valid bundled index");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            service.close();
        }
    }

    @Test
    void unpinnedFallbackExtractsAReadablePrivateIndexAndRemovesItOnClose() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        LuceneSearchService service = new LuceneSearchService();
        service.indexDownloader = failingDownloader(false, new IOException("Release unavailable"));
        Thread.currentThread().setContextClassLoader(classpathIndex(new AtomicInteger()));
        Path extracted = null;
        try {
            IndexDownloader.ResolvedIndex resolved = resolveIndex(service);
            extracted = resolved.dir();
            // Keep model initialization out of this fixture; exercise the real resolved-index cleanup policy.
            setResolvedIndex(service, resolved);
            assertTrue(resolved.temporary());
            assertTrue(extracted.getFileName().toString().startsWith("knowledge-index"));
            if (Files.getFileAttributeView(extracted, PosixFileAttributeView.class) != null) {
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(extracted));
            }
            try (FSDirectory directory = FSDirectory.open(extracted);
                 DirectoryReader reader = DirectoryReader.open(directory)) {
                assertEquals(1, reader.numDocs());
                assertEquals("fallback-canary", reader.storedFields().document(0).get("id"));
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            service.close();
        }
        assertNotNull(extracted);
        assertFalse(Files.exists(extracted), "Only the extracted temporary index must be deleted on close");
    }

    @Test
    void closingTheServicePreservesAnExplicitPersistentIndex() throws Exception {
        Path persistent = Files.createDirectory(temp.resolve("persistent"));
        Path sentinel = Files.writeString(persistent.resolve("sentinel"), "keep");
        LuceneSearchService service = new LuceneSearchService();
        service.indexDownloader = new IndexDownloader(null) {
            @Override
            public ResolvedIndex resolve() {
                return new ResolvedIndex(persistent, false);
            }
        };
        IndexDownloader.ResolvedIndex resolved = resolveIndex(service);
        assertEquals(persistent, resolved.dir());
        assertFalse(resolved.temporary());
        setResolvedIndex(service, resolved);
        service.close();
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test
    void missingBundledIndexCannotTurnFailedAcquisitionIntoSuccessfulStartup() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        LuceneSearchService service = new LuceneSearchService();
        service.indexDownloader = failingDownloader(false, new IOException("Release unavailable"));
        Thread.currentThread().setContextClassLoader(new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return name.startsWith("knowledge-index/") ? null : super.getResourceAsStream(name);
            }
        });
        Path extracted;
        try {
            RuntimeException thrown = assertThrows(RuntimeException.class, service::init);
            assertInstanceOf(IndexNotFoundException.class, thrown.getCause());
            Field field = LuceneSearchService.class.getDeclaredField("resolvedIndex");
            field.setAccessible(true);
            extracted = ((IndexDownloader.ResolvedIndex) field.get(service)).dir();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            service.close();
        }
        assertFalse(Files.exists(extracted));
    }

    private ClassLoader classpathIndex(AtomicInteger resourceReads) throws Exception {
        Path source = Files.createTempDirectory(temp, "bundled-");
        try (FSDirectory directory = FSDirectory.open(source);
             StandardAnalyzer analyzer = new StandardAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            writer.addDocument(new KnowledgeDocument("fallback-canary", "apache_camel")
                    .docType("guide").content("bundled fallback").build());
        }
        Map<String, byte[]> resources = new HashMap<>();
        StringBuilder manifest = new StringBuilder();
        try (var files = Files.list(source)) {
            for (Path file : files.filter(path -> !path.getFileName().toString().equals("write.lock")).toList()) {
                String name = file.getFileName().toString();
                manifest.append(name).append('\n');
                resources.put("knowledge-index/" + name, Files.readAllBytes(file));
            }
        }
        resources.put("knowledge-index/INDEX_FILES", manifest.toString().getBytes(StandardCharsets.UTF_8));
        return new ClassLoader(Thread.currentThread().getContextClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.startsWith("knowledge-index/")) {
                    resourceReads.incrementAndGet();
                    byte[] bytes = resources.get(name);
                    return bytes != null ? new ByteArrayInputStream(bytes) : null;
                }
                return super.getResourceAsStream(name);
            }
        };
    }

    private static IndexDownloader failingDownloader(boolean pinned, IOException failure) {
        return new IndexDownloader(null) {
            @Override
            public ResolvedIndex resolve() throws IOException {
                throw failure;
            }

            @Override
            boolean isVersionPinned() {
                return pinned;
            }
        };
    }

    private static IndexDownloader.ResolvedIndex resolveIndex(LuceneSearchService service) throws Exception {
        Method method = LuceneSearchService.class.getDeclaredMethod("resolveIndex");
        method.setAccessible(true);
        try {
            return (IndexDownloader.ResolvedIndex) method.invoke(service);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void setResolvedIndex(LuceneSearchService service, IndexDownloader.ResolvedIndex resolved)
            throws Exception {
        Field field = LuceneSearchService.class.getDeclaredField("resolvedIndex");
        field.setAccessible(true);
        field.set(service, resolved);
    }
}
