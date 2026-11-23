package org.apache.camel.kit.workflow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** The workflow, provider bindings and installable files selected together by the distribution build. */
public final class SkillCatalog {

    public static final String DEFAULT = "META-INF/camel-kit/default-catalog.zip";
    public static final String DISTRIBUTION = "META-INF/camel-kit/catalog.zip";
    static final String MANIFEST = "catalog.json";
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static SkillCatalog bundled;

    public record Provider(String service, String implementation) {
    }

    public record Binding(String pack, int contract, List<String> requires, String path, Provider provider) {
        public Binding {
            requires = List.copyOf(requires);
        }
    }

    public record Manifest(int contract, List<String> packs, WorkflowManifest workflow,
            Map<String, Binding> contributions) {
        public Manifest {
            packs = List.copyOf(packs);
            contributions = Map.copyOf(contributions);
        }
    }

    private final Manifest manifest;
    private final Map<String, byte[]> files;

    SkillCatalog(Manifest manifest, Map<String, byte[]> files) throws IOException {
        if (manifest.contract() != 1) {
            throw new IOException("Unsupported skill catalog contract: " + manifest.contract());
        }
        WorkflowManifestValidator.validateOrThrow(manifest.workflow());
        Set<String> skills = new HashSet<>();
        for (var skill : manifest.workflow().skills()) {
            skills.add(skill.name());
            if (!files.containsKey(skill.name() + "/SKILL.md")) {
                throw new IOException("Missing SKILL.md for skill/" + skill.name());
            }
        }
        for (var entry : manifest.contributions().entrySet()) {
            Binding binding = entry.getValue();
            if (binding.contract() != 1 || !manifest.packs().contains(binding.pack())
                    || !manifest.contributions().keySet().containsAll(binding.requires())
                    || (binding.provider() == null ? !files.containsKey(binding.path()) : binding.path() != null)) {
                throw new IOException("Invalid bundled contribution " + binding.pack() + ": " + entry.getKey());
            }
        }
        // All supported hosts must agree on whether a destination is a file or directory, including on Windows.
        Map<String, String> spellings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            if (!safePath(path) || slash < 0
                    || !(skills.contains(path.substring(0, slash)) || path.startsWith("shared/"))) {
                throw new IOException("Invalid skill resource destination: " + path);
            }
            for (String current = path;; current = current.substring(0, current.lastIndexOf('/'))) {
                String previous = spellings.putIfAbsent(current, current);
                if (previous != null && !previous.equals(current)) {
                    throw new IOException("Skill resource paths differ only by case: " + previous + " and " + current);
                }
                if (!current.contains("/")) {
                    break;
                }
            }
            for (int i = slash; i >= 0; i = path.indexOf('/', i + 1)) {
                if (files.containsKey(path.substring(0, i))) {
                    throw new IOException("Skill resource is both file and directory: " + path.substring(0, i));
                }
            }
        }
        this.manifest = manifest;
        this.files = Collections.unmodifiableMap(new TreeMap<>(files));
    }

    public Manifest manifest() {
        return manifest;
    }

    public Set<String> paths() {
        return files.keySet();
    }

    public InputStream open(String path) throws IOException {
        byte[] bytes = files.get(path);
        if (bytes == null) {
            throw new IOException("Unknown skill resource: " + path);
        }
        return new ByteArrayInputStream(bytes);
    }

    public static boolean safePath(String path) {
        return path != null && path.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]*(/[a-zA-Z0-9_-][a-zA-Z0-9_.-]*)*");
    }

    public static synchronized SkillCatalog bundled() throws IOException {
        if (bundled == null) {
            bundled = load(SkillCatalog.class.getClassLoader());
        }
        return bundled;
    }

    static SkillCatalog load(ClassLoader loader) throws IOException {
        List<URL> urls = Collections.list(loader.getResources(DISTRIBUTION));
        if (urls.isEmpty()) {
            urls = Collections.list(loader.getResources(DEFAULT));
        }
        if (urls.size() != 1) {
            throw new IOException("Expected one assembled Camel Kit skill catalog, found " + urls);
        }
        try (var stream = new ZipInputStream(urls.get(0).openStream())) {
            Manifest manifest = null;
            Map<String, byte[]> files = new TreeMap<>();
            for (ZipEntry entry; (entry = stream.getNextEntry()) != null;) {
                String name = entry.getName();
                if (name.equals(MANIFEST) && manifest == null) {
                    manifest = JSON.readValue(stream.readAllBytes(), Manifest.class);
                } else if (!name.startsWith("skills/")
                        || files.putIfAbsent(name.substring(7), stream.readAllBytes()) != null) {
                    throw new IOException("Unexpected or duplicate skill catalog entry: " + name);
                }
            }
            if (manifest == null) {
                throw new IOException("Missing " + MANIFEST + " in " + urls);
            }
            return new SkillCatalog(manifest, files);
        }
    }
}
