package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Path;

/** Original entry points for callers compiled against the generator package. */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    public static void write(Path target, String content) throws IOException {
        org.apache.camel.kit.util.AtomicFileWriter.write(target, content);
    }

    public static void write(Path target, byte[] content) throws IOException {
        org.apache.camel.kit.util.AtomicFileWriter.write(target, content);
    }

    public static void replace(Path temporary, Path target) throws IOException {
        org.apache.camel.kit.util.AtomicFileWriter.replace(temporary, target);
    }
}
