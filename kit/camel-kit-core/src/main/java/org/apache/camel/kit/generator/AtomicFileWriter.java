package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.UUID;

public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    public static void write(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] content) throws IOException {
        Path temporary;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // Keep replacement content private until the existing target's mode is applied.
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        } else {
            temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.createFile(temporary);
        }
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void replace(Path temporary, Path target) throws IOException {
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            PosixFileAttributeView permissions = Files.getFileAttributeView(
                    target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (permissions != null) {
                Files.setPosixFilePermissions(temporary, permissions.readAttributes().permissions());
            }
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
