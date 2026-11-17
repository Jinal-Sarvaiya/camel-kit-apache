package org.apache.camel.kit;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ArgumentFilesTest {
    @TempDir
    Path directory;

    private static String escaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    @Test
    void preservesLiteralsQuotesCommentsAndRecursiveIncludes() throws Exception {
        Path nested = Files.writeString(directory.resolve("nested"), "'hello world' \"a#b\" '' @@literal");
        Path outer = directory.resolve("outer");
        Files.writeString(outer, "first# inline comment\n'@" + escaped(nested) + "' '@" + escaped(nested) + "' '@"
                                 + escaped(outer) + "' last");
        String missing = "@" + directory.resolve("missing");
        assertArrayEquals(new String[]{
                "@", "@literal", missing, "first", "hello world", "a#b", "", "@literal", "last",
                "hello world", "a#b", "", "@literal"},
                ArgumentFiles.expand(new String[]{"@", "@@literal", missing, "@" + outer, "@" + nested}));
    }
}
