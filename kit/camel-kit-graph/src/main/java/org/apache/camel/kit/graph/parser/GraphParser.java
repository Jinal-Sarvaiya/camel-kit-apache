package org.apache.camel.kit.graph.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.camel.kit.graph.ProjectGraph;

public interface GraphParser {
    int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    long MAX_TOTAL_SOURCE_BYTES = 256L * 1024 * 1024;
    int MAX_PROJECT_ENTRIES = 100_000;

    void parse(Path projectRoot, ProjectGraph graph);

    default ProjectGraph parseFragment(Path projectRoot, ProjectGraph baseGraph) {
        return parseFragment(projectRoot, baseGraph, scannedFilePaths(projectRoot, projectFiles(projectRoot)));
    }

    default ProjectGraph parseFragment(Path projectRoot, ProjectGraph baseGraph, List<Path> scannedFiles) {
        ProjectGraph fragment = new ProjectGraph();
        fragment.merge(baseGraph);
        parseFiles(projectRoot, scannedFiles, fragment);
        return fragment;
    }

    default void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph) {
        parse(projectRoot, graph);
    }

    default List<String> scannedFiles(Path projectRoot) {
        return List.of();
    }

    default List<Path> scannedFilePaths(Path projectRoot, List<Path> projectFiles) {
        return scannedFiles(projectRoot).stream()
                .map(projectRoot::resolve)
                .toList();
    }

    default List<String> warnings() {
        return List.of();
    }

    default void resetWarnings() {
    }

    static List<Path> projectFiles(Path projectRoot) {
        if (!Files.exists(projectRoot)) {
            return List.of();
        }

        Path realRoot;
        try {
            realRoot = projectRoot.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve graph project root: " + projectRoot, e);
        }
        List<Path> files = new ArrayList<>();
        Set<String> excludedDirectories = Set.of(".git", ".svn", ".hg", "target", "build", "node_modules");
        try {
            Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
                private int entries;
                private long sourceBytes;

                private void countEntry() {
                    if (++entries > MAX_PROJECT_ENTRIES) {
                        throw new IllegalArgumentException(
                                "Project exceeds " + MAX_PROJECT_ENTRIES + " filesystem entries");
                    }
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    countEntry();
                    return !dir.equals(realRoot) && excludedDirectories.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    countEntry();
                    Path path = projectRoot.resolve(realRoot.relativize(file));
                    if (attributes.isRegularFile()) {
                        if (isSourceFile(path)) {
                            sourceBytes += requireSafeFile(projectRoot, path);
                            if (sourceBytes > MAX_TOTAL_SOURCE_BYTES) {
                                throw new IllegalArgumentException(
                                        "Project source exceeds " + MAX_TOTAL_SOURCE_BYTES + " bytes");
                            }
                        }
                        files.add(path);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return files.stream().sorted().toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan files under " + projectRoot, e);
        }
    }

    static List<String> findFiles(Path projectRoot, Predicate<Path> matcher) {
        return relativeFileNames(projectRoot, findFilePaths(projectRoot, projectFiles(projectRoot), matcher));
    }

    /** The matcher must inspect names only; validate candidates before any content sniffing. */
    static List<Path> findFilePaths(Path projectRoot, List<Path> projectFiles, Predicate<Path> matcher) {
        return projectFiles.stream()
                .filter(matcher)
                .peek(file -> requireSafeFile(projectRoot, file))
                .sorted()
                .toList();
    }

    static void requireSafeFiles(Path projectRoot, List<Path> files) {
        long bytes = 0;
        if (files.size() > MAX_PROJECT_ENTRIES) {
            throw new IllegalArgumentException("Too many graph input files");
        }
        for (Path file : files) {
            bytes += requireSafeFile(projectRoot, file);
            if (bytes > MAX_TOTAL_SOURCE_BYTES) {
                throw new IllegalArgumentException("Project source exceeds " + MAX_TOTAL_SOURCE_BYTES + " bytes");
            }
        }
    }

    private static long requireSafeFile(Path projectRoot, Path file) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path input = file.toAbsolutePath().normalize();
        if (!input.startsWith(root) || input.equals(root)) {
            throw new IllegalArgumentException("Graph input is outside the project: " + file);
        }
        Path current = root;
        for (Path component : root.relativize(input)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Graph input is a symbolic link: " + current);
            }
        }
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(projectRoot.toRealPath())) {
                throw new IllegalArgumentException("Graph input is not a confined regular file: " + file);
            }
            long bytes = Files.size(file);
            if (bytes > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("Graph input exceeds " + MAX_SOURCE_BYTES + " bytes: " + file);
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot validate graph input: " + file, e);
        }
    }

    static byte[] readSource(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw new IOException("Graph input exceeds " + MAX_SOURCE_BYTES + " bytes: " + file);
            }
            return bytes;
        }
    }

    private static boolean isSourceFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".groovy") || name.endsWith(".xml")
                || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".properties")
                || name.endsWith(".dwl") || name.endsWith(".odx") || name.endsWith(".btm") || name.endsWith(".btp");
    }

    static List<String> relativeFileNames(Path projectRoot, List<Path> files) {
        return files.stream()
                .map(file -> relativeFileName(projectRoot, file))
                .sorted()
                .toList();
    }

    static String relativeFileName(Path projectRoot, Path file) {
        return projectRoot.relativize(file).toString().replace('\\', '/');
    }
}
