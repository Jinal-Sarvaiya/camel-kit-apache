package org.apache.camel.kit.generator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicFileWriterTest {
    @TempDir
    Path directory;

    @Test
    void freshFilesUseTheSamePermissionsAsOrdinaryCreation() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        Path ordinary = Files.writeString(directory.resolve("ordinary"), "content");
        Path generated = directory.resolve("generated");

        AtomicFileWriter.write(generated, "content");

        assertEquals(Files.getPosixFilePermissions(ordinary), Files.getPosixFilePermissions(generated));
        assertEquals("content", Files.readString(generated));
    }

    @Test
    void replacementPreservesModeWithoutModifyingTheLinkedInode() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        Path original = Files.writeString(directory.resolve("outside"), "original");
        var mode = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(original, mode);
        Path output = Files.createLink(directory.resolve("generated"), original);

        AtomicFileWriter.write(output, "generated content");

        assertEquals("original", Files.readString(original));
        assertEquals("generated content", Files.readString(output));
        assertEquals(mode, Files.getPosixFilePermissions(output));
        assertEquals(mode, Files.getPosixFilePermissions(original));
        assertFalse(Files.isSameFile(original, output));
    }
}
