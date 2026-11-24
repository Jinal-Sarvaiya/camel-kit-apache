package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
        for (var entry : new TreeMap<>(SkillCatalog.bundled().manifest().contributions()).entrySet()) {
            var binding = entry.getValue();
            var provider = binding.provider();
            if (provider == null || !MigrationParser.class.getName().equals(provider.service())) {
                continue;
            }
            if (!entry.getKey().matches("provider/" + SkillPack.ID)) {
                throw new IOException(
                        "Invalid migration parser contribution in " + binding.pack() + ": " + entry.getKey());
            }
            String id = entry.getKey().substring("provider/".length());
            try {
                var type = Class.forName(provider.implementation(), false, MigrationParsers.class.getClassLoader())
                        .asSubclass(MigrationParser.class);
                parsers.put(id, type.getConstructor().newInstance());
            } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
                Throwable cause
                        = e instanceof InvocationTargetException invocation ? invocation.getTargetException() : e;
                throw new IOException(
                        "Cannot load " + binding.pack() + ": " + entry.getKey() + " ("
                                      + provider.implementation() + "): " + cause,
                        cause);
            }
        }
        if (requiredId != null && !parsers.containsKey(requiredId)) {
            throw new IOException(
                    "Required migration parser provider/" + requiredId + " is unavailable in this distribution");
        }
        return new GraphBuilder(parsers);
    }
}
