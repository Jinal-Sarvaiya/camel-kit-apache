package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/** Versioned declarations of one build-time module. Keys are stable across explicitly selected replacements. */
record SkillPack(String id, int schema, List<String> requires, Map<String, Entry> add, Map<String, Entry> replace,
        List<String> exclude) {

    static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.SPLIT_LINES)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    static final String ID = "[a-z][a-z0-9-]*";

    record Entry(int contract, String resource, SkillCatalog.Provider provider, List<String> requires,
            WorkflowHook hook, List<String> handles) {
        Entry {
            handles = handles == null ? List.of() : List.copyOf(handles);
            requires = requires == null ? List.of() : List.copyOf(requires);
        }
    }

    record Declaration(String pack, String key, Entry entry) {
        String kind() {
            return key.substring(0, key.indexOf('/'));
        }

        String name() {
            return key.substring(key.indexOf('/') + 1);
        }

        IOException error(String message) {
            return new IOException(pack + ": " + key + ": " + message);
        }

        String location() {
            return pack + ": " + key;
        }
    }

    SkillPack {
        requires = requires == null ? List.of() : List.copyOf(requires);
        add = add == null ? Map.of() : Map.copyOf(add);
        replace = replace == null ? Map.of() : Map.copyOf(replace);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    static Map<String, SkillPack> load(ClassLoader loader, List<String> selected) throws IOException {
        Map<String, SkillPack> packs = new TreeMap<>();
        for (String id : selected) {
            if (!id.matches(ID) || packs.containsKey(id)) {
                throw new IOException("Invalid or duplicate selected pack ID: " + id);
            }
            String path = directory(id) + "pack.yaml";
            try (var input = uniqueResource(loader, path).openStream()) {
                SkillPack pack = YAML.readValue(input, SkillPack.class);
                if (!id.equals(pack.id()) || pack.schema() != 1) {
                    throw new IOException(
                            "Expected pack '" + id + "' schema 1, found '" + pack.id() + "' schema " + pack.schema());
                }
                packs.put(id, pack);
            } catch (IOException | RuntimeException e) {
                throw new IOException(path + ": " + e.getMessage(), e);
            }
        }
        Set<String> checked = new HashSet<>();
        for (String id : packs.keySet()) {
            checkDependencies(packs, id, checked, new ArrayList<>());
        }
        return packs;
    }

    private static void checkDependencies(
            Map<String, SkillPack> packs, String id, Set<String> checked, List<String> path)
            throws IOException {
        if (checked.contains(id)) {
            return;
        }
        if (path.contains(id)) {
            throw new IOException("Cyclic pack dependency: " + String.join(" -> ", path) + " -> " + id);
        }
        if (!packs.containsKey(id)) {
            throw new IOException("Missing pack dependency: " + String.join(" -> ", path) + " -> " + id);
        }
        path.add(id);
        for (String required : packs.get(id).requires()) {
            checkDependencies(packs, required, checked, path);
        }
        path.remove(path.size() - 1);
        checked.add(id);
    }

    static String directory(String id) {
        return "META-INF/camel-kit/packs/" + id + "/";
    }

    static URL uniqueResource(ClassLoader loader, String path) throws IOException {
        List<URL> urls = Collections.list(loader.getResources(path));
        if (urls.size() != 1) {
            throw new IOException("Expected one resource " + path + ", found " + urls);
        }
        return urls.get(0);
    }

    static Map<String, Declaration> definitions(Map<String, SkillPack> packs, Map<String, Declaration> core)
            throws IOException {
        Map<String, Declaration> definitions = new TreeMap<>(core);
        for (SkillPack pack : packs.values()) {
            for (var entry : pack.add().entrySet()) {
                register(definitions, new Declaration(pack.id(), entry.getKey(), entry.getValue()));
            }
        }
        return definitions;
    }

    static Map<String, Declaration> resolve(
            Map<String, SkillPack> packs, Map<String, Declaration> definitions,
            WorkflowManifest workflow)
            throws IOException {
        Map<String, Declaration> effective = new TreeMap<>(definitions);
        Map<String, Declaration> edits = new TreeMap<>();
        for (SkillPack pack : packs.values()) {
            for (var entry : pack.replace().entrySet()) {
                register(edits, new Declaration(pack.id(), entry.getKey(), entry.getValue()));
            }
            for (String key : pack.exclude()) {
                register(edits, new Declaration(pack.id(), key, null));
            }
        }
        for (Declaration edit : edits.values()) {
            Declaration original = definitions.get(edit.key());
            if (original == null) {
                throw edit.error("no such contribution to replace or exclude");
            }
            if (!packs.get(edit.pack()).requires().contains(original.pack())) {
                throw edit.error("requires must name the declaring pack '" + original.pack() + "'");
            }
            if (original.entry().provider() != null && edit.entry() != null
                    && !original.entry().provider().service().equals(edit.entry().provider().service())) {
                throw edit.error("replacement must preserve service " + original.entry().provider().service()
                                 + " declared by " + original.location());
            }
            WorkflowHook originalHook = original.entry().hook();
            if (originalHook != null && edit.entry() != null) {
                WorkflowHook replacement = edit.entry().hook();
                if (replacement == null || !replacement.checks().equals(originalHook.checks())
                        || !Set.copyOf(replacement.targets()).equals(Set.copyOf(originalHook.targets()))) {
                    throw edit.error(
                            "replacement must preserve hook targets and checks declared by " + original.location());
                }
            }
            if (edit.entry() == null) {
                if (edit.kind().equals("skill")) {
                    for (var stage : workflow.stages()) {
                        if (stage.skill().equals(edit.name())) {
                            throw edit.error("required by core workflow stage " + stage.id());
                        }
                    }
                    for (var artifact : workflow.artifacts()) {
                        if (artifact.producedBy().contains(edit.name())
                                || artifact.consumedBy().contains(edit.name())) {
                            throw edit.error("required by core workflow artifact " + artifact.id());
                        }
                    }
                }
                effective.remove(edit.key());
            } else {
                effective.put(edit.key(), edit);
            }
        }
        // Removing a skill also removes the guides supplied by its own pack.
        for (Declaration d : List.copyOf(effective.values())) {
            if (d.kind().equals("resource")) {
                String owner = d.name().split("/", 2)[0];
                if (!owner.equals("shared") && !effective.containsKey("skill/" + owner)) {
                    Declaration skill = definitions.get("skill/" + owner);
                    if (skill == null || !skill.pack().equals(d.pack())) {
                        throw d.error("resource requires effective skill/" + owner);
                    }
                    effective.remove(d.key());
                }
            }
        }
        return effective;
    }

    static void requireContribution(
            Declaration consumer, String key, Map<String, Declaration> effective,
            Map<String, SkillPack> packs)
            throws IOException {
        if (!effective.containsKey(key)) {
            String owner = key.startsWith("resource/") ? "skill/" + key.split("/", 3)[1] : key;
            for (SkillPack pack : packs.values()) {
                String excluded = pack.exclude().contains(key) ? key : owner;
                if (pack.exclude().contains(excluded)) {
                    throw consumer.error("requires missing contribution " + key + " (excluded by "
                                         + pack.id() + ": " + excluded + ")");
                }
            }
            throw consumer.error("requires missing contribution " + key);
        }
    }

    private static void register(Map<String, Declaration> entries, Declaration next) throws IOException {
        Declaration previous = entries.putIfAbsent(next.key(), next);
        if (previous != null) {
            throw next.error("conflicts with " + previous.location());
        }
    }
}
