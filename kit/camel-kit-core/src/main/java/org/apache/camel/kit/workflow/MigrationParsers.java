package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import org.apache.camel.kit.graph.GraphBuilder;
import org.apache.camel.kit.graph.parser.MigrationParser;

/** Resolves migration parsers from the distribution's assembled catalog for a single graph build. */
public final class MigrationParsers {
    private MigrationParsers() {
    }

    public static GraphBuilder newGraphBuilder() throws IOException {
        return newGraphBuilder(null);
    }

    public static GraphBuilder newGraphBuilder(String requiredId) throws IOException {
        Map<String, MigrationParser> parsers = new TreeMap<>();
        for (var entry : SkillCatalog.bundled().providers(MigrationParser.class).entrySet()) {
            parsers.put(entry.getKey(), entry.getValue().instantiate(entry.getKey(), MigrationParser.class));
        }
        if (requiredId != null && !parsers.containsKey(requiredId)) {
            throw new IOException(
                    "Required migration parser provider/" + requiredId + " is unavailable in this distribution");
        }
        return new GraphBuilder(parsers);
    }
}
