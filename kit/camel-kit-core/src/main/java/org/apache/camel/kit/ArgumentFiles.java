package org.apache.camel.kit;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StreamTokenizer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Standalone argument files retain the existing quoting, comment and unreadable-file behavior. */
final class ArgumentFiles {
    private ArgumentFiles() {
    }

    static String[] expand(String[] arguments) throws IOException {
        List<String> expanded = new ArrayList<>();
        for (String argument : arguments) {
            expand(argument, expanded, new HashSet<>());
        }
        return expanded.toArray(String[]::new);
    }

    private static void expand(String argument, List<String> expanded, Set<String> visited) throws IOException {
        if (!argument.startsWith("@") || argument.length() == 1) {
            expanded.add(argument);
        } else if (argument.startsWith("@@")) {
            expanded.add(argument.substring(1));
        } else {
            File file = new File(argument.substring(1));
            if (!file.canRead()) {
                expanded.add(argument);
            } else if (visited.add(file.getAbsolutePath())) {
                try (var reader = new FileReader(file, Charset.defaultCharset())) {
                    var tokenizer = new StreamTokenizer(reader);
                    tokenizer.resetSyntax();
                    tokenizer.wordChars(' ', 255);
                    tokenizer.whitespaceChars(0, ' ');
                    tokenizer.quoteChar('"');
                    tokenizer.quoteChar('\'');
                    tokenizer.commentChar('#');
                    while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
                        expand(tokenizer.sval, expanded, visited);
                    }
                }
            }
        }
    }
}
