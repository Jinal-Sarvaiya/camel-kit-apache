package org.apache.camel.kit.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Preflight for generated paths below an explicitly selected destination. */
public final class ConfinedPaths {
    private ConfinedPaths() {
    }

    public static void requirePath(Path destination, Path output) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Path target = output.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Generated path is outside the destination: " + output);
        }
        Path current = root;
        for (Path component : root.relativize(target)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing generated path through symbolic link: " + current);
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Generated path is not a regular file or directory: " + current);
            }
        }
    }

    public static void requireTree(Path destination, Path output) throws IOException {
        requirePath(destination, output);
        if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(output)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    requirePath(destination, iterator.next());
                }
            }
        }
    }

    public static void requireVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid catalog version: " + version);
        }
    }
}
