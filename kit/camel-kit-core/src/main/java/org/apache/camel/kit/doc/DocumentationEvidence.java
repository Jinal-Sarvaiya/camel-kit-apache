package org.apache.camel.kit.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Binds a documentation review to its inputs; it does not evaluate the review's conclusions. */
public final class DocumentationEvidence {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final String SUFFIX = ".reconciliation.json";

    private DocumentationEvidence() {
    }

    public record Snapshot(int schemaVersion, List<String> sources, Map<String, String> files) {
    }

    public record Review(int schemaVersion, String reviewedAt, String document, String documentDigest,
            String evidence, String evidenceDigest, String result, Snapshot snapshot) {
    }

    public record Status(String status, String reason, String reviewedAt, String evidence, List<String> sources) {
    }

    public static Path resolve(Path project, Path path) throws IOException {
        Path root = project.toRealPath();
        for (Path part : path) {
            if (part.toString().equals("..")) {
                throw new IOException("Parent traversal is not allowed: " + path);
            }
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("Expected a path inside the project: " + path);
        }
        Path cursor = root;
        for (Path part : root.relativize(resolved)) {
            cursor = cursor.resolve(part);
            if (part.toString().equals(".git") || Files.isSymbolicLink(cursor)) {
                throw new IOException("Git internals and symbolic links are not review inputs: " + path);
            }
        }
        return resolved;
    }

    public static Snapshot snapshot(Path project, List<String> sources) throws IOException {
        Path root = project.toRealPath();
        if (sources == null || sources.isEmpty() || sources.size() > MAX_ENTRIES) {
            throw new IOException("At least one bounded source file or directory is required");
        }
        Map<String, String> files = new TreeMap<>();
        long total = 0;
        for (String source : sources) {
            Path input = resolve(root, Path.of(source));
            if (!Files.exists(input, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing source: " + source);
            }
            try (var paths = Files.walk(input)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path file = resolve(root, iterator.next());
                    String name = relative(root, file);
                    if (files.containsKey(name)) {
                        continue;
                    }
                    if (files.size() >= MAX_ENTRIES) {
                        throw new IOException("Review scope exceeds " + MAX_ENTRIES + " entries");
                    }
                    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                        files.put(name, "directory");
                    } else {
                        total += Files.size(file);
                        if (total > MAX_TOTAL_BYTES) {
                            throw new IOException("Review scope exceeds 512 MiB");
                        }
                        files.put(name, digest(file));
                    }
                }
            }
        }
        List<String> canonical = sources.stream().map(source -> relative(root, root.resolve(source).normalize()))
                .distinct().sorted().toList();
        return new Snapshot(1, canonical, files);
    }

    public static String json(Object value) throws IOException {
        String serialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= MAX_METADATA_BYTES) {
            throw new IOException("Review metadata exceeds 1 MiB");
        }
        return serialized;
    }

    public static void record(Path project, Path document, Path snapshotFile, Path evidenceFile, String result)
            throws IOException {
        Path root = project.toRealPath();
        Path doc = resolve(root, document);
        Path evidence = resolve(root, evidenceFile);
        Path input = resolve(root, snapshotFile);
        Path receipt = resolve(root, Path.of(doc + SUFFIX));
        if (!List.of("complete", "partial", "failed").contains(result)) {
            throw new IOException("Result must be complete, partial or failed");
        }
        Snapshot before = JSON.readValue(readMetadata(input), Snapshot.class);
        validate(before);
        for (String source : before.sources()) {
            Path scope = resolve(root, Path.of(source));
            if (doc.startsWith(scope) || evidence.startsWith(scope) || input.startsWith(scope)
                    || receipt.startsWith(scope)) {
                throw new IOException("Keep the document, report and snapshot outside the source scope");
            }
        }
        if (doc.equals(evidence) || doc.equals(input) || evidence.equals(input) || receipt.equals(evidence)
                || receipt.equals(input)) {
            throw new IOException("Document, report, snapshot and receipt must be separate files");
        }
        if (readMetadata(evidence).isBlank() || readMetadata(doc).isBlank()) {
            throw new IOException("Document and review evidence must not be empty");
        }
        if (!before.equals(snapshot(root, before.sources()))) {
            throw new IOException("Sources changed during review; inspect them again using a new snapshot");
        }
        Review review = new Review(
                1, Instant.now().toString(), relative(root, doc), digest(doc),
                relative(root, evidence), digest(evidence), result, before);
        Path temporary = Files.createTempFile(receipt.getParent(), ".reconciliation-", ".tmp");
        try {
            Files.writeString(temporary, json(review) + "\n");
            Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Status check(Path project, Path document) {
        try {
            Path root = project.toRealPath();
            Path doc = resolve(root, document);
            Path receipt = resolve(root, Path.of(doc + SUFFIX));
            if (!Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
                return unverified("No recorded documentation review");
            }
            Review review = JSON.readValue(readMetadata(receipt), Review.class);
            if (review == null || review.schemaVersion() != 1 || !relative(root, doc).equals(review.document())
                    || review.reviewedAt() == null || review.evidence() == null
                    || !List.of("complete", "partial", "failed").contains(review.result())) {
                return unverified("Invalid documentation review");
            }
            Instant.parse(review.reviewedAt());
            validate(review.snapshot());
            if (!review.result().equals("complete")) {
                return new Status(
                        "unverified", "Review result: " + review.result(), review.reviewedAt(), review.evidence(),
                        review.snapshot().sources());
            }
            boolean unchanged = review.documentDigest().equals(digest(doc))
                    && review.evidenceDigest().equals(digest(resolve(root, Path.of(review.evidence()))))
                    && review.snapshot().equals(snapshot(root, review.snapshot().sources()));
            return new Status(
                    unchanged ? "unchanged" : "stale",
                    unchanged
                            ? "Recorded review inputs are unchanged; this is not a semantic correctness guarantee"
                            : "Document, evidence or source inventory changed since review",
                    review.reviewedAt(), review.evidence(), review.snapshot().sources());
        } catch (Exception e) {
            return unverified("Review unavailable: " + e.getMessage());
        }
    }

    private static Status unverified(String reason) {
        return new Status("unverified", reason, null, null, List.of());
    }

    private static void validate(Snapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.schemaVersion() != 1 || snapshot.sources() == null
                || snapshot.sources().isEmpty() || snapshot.sources().size() > MAX_ENTRIES || snapshot.files() == null
                || snapshot.files().isEmpty() || snapshot.files().size() > MAX_ENTRIES) {
            throw new IOException("Invalid source snapshot");
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static String readMetadata(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular file: " + file);
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) {
                throw new IOException("Review metadata exceeds 1 MiB");
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String digest(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular file: " + file);
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                total += length;
                if (total > MAX_FILE_BYTES) {
                    throw new IOException("Source file exceeds 64 MiB: " + file);
                }
                digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
