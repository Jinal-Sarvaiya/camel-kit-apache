package org.apache.camel.kit.workflow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.camel.kit.graph.GraphTypeRegistry;
import org.apache.camel.kit.graph.model.GraphTypeAdapter;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    public record Binding(String pack, int contract, List<String> requires, String path, Provider provider,
            @JsonInclude(JsonInclude.Include.NON_NULL) WorkflowHook hook,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> handles) {
        public Binding(String pack, int contract, List<String> requires, String path, Provider provider) {
            this(pack, contract, requires, path, provider, null, List.of());
        }

        <T> T instantiate(String id, Class<T> service) throws IOException {
            try {
                var type = Class.forName(provider.implementation(), false, SkillCatalog.class.getClassLoader())
                        .asSubclass(service);
                return type.getConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
                Throwable cause
                        = e instanceof InvocationTargetException invocation ? invocation.getTargetException() : e;
                throw new IOException(
                        "Cannot load " + pack + ": provider/" + id + " (" + provider.implementation()
                                      + "): " + cause,
                        cause);
            }
        }

        public Binding {
            handles = handles == null ? List.of() : List.copyOf(handles);
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

    private final String archiveSha256;
    private final Manifest manifest;
    private final Map<String, byte[]> files;

    SkillCatalog(Manifest manifest, Map<String, byte[]> files, String archiveSha256) throws IOException {
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
                    || (binding.provider() == null
                            ? binding.path() == null || !files.containsKey(binding.path()) : binding.path() != null)) {
                throw new IOException("Invalid bundled contribution " + binding.pack() + ": " + entry.getKey());
            }
            if (!binding.handles().isEmpty() && (!(entry.getKey().startsWith("skill/")
                    || entry.getKey().startsWith("provider/") && binding.provider() != null
                            && WorkflowHookProvider.class.getName().equals(binding.provider().service()))
                    || binding.handles().stream().anyMatch(key -> !key.matches(WorkflowHook.STEP))
                    || Set.copyOf(binding.handles()).size() != binding.handles().size())) {
                throw new IOException(binding.pack() + ": invalid handles declaration on " + entry.getKey());
            }
            WorkflowHook hook = binding.hook();
            if (hook != null) {
                hook.validate(entry.getKey());
                String stage = entry.getKey().split("/")[1];
                boolean atStage = manifest.workflow().stages().stream().anyMatch(s -> s.id().equals(stage)
                        && (s.skill() + "/SKILL.md").equals(binding.path()));
                if (!atStage) {
                    throw new IOException(
                            binding.pack() + ": " + entry.getKey() + ": expected the skill entry for stage "
                                          + stage + ", got " + binding.path());
                }
                if (!binding.requires().contains(hook.handler())
                        || !hook.supports(entry.getKey(), manifest.contributions().get(hook.handler()), skills)) {
                    throw new IOException(
                            binding.pack() + ": " + entry.getKey() + ": unsupported hook handler " + hook.handler());
                }
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
        this.archiveSha256 = archiveSha256;
        this.manifest = manifest;
        this.files = Collections.unmodifiableMap(new TreeMap<>(files));
    }

    String archiveSha256() {
        return archiveSha256;
    }

    public Manifest manifest() {
        return manifest;
    }

    Map<String, Binding> providers(Class<?> service) throws IOException {
        Map<String, Binding> bindings = new TreeMap<>();
        for (var entry : manifest.contributions().entrySet()) {
            Binding binding = entry.getValue();
            if (binding.provider() != null && service.getName().equals(binding.provider().service())) {
                if (!entry.getKey().matches("provider/" + SkillPack.ID)) {
                    throw new IOException(
                            "Invalid " + service.getSimpleName() + " contribution in " + binding.pack() + ": "
                                          + entry.getKey());
                }
                bindings.put(entry.getKey().substring("provider/".length()), binding);
            }
        }
        return bindings;
    }

    public GraphTypeRegistry graphTypes() throws IOException {
        var bindings = providers(GraphTypeAdapter.class);
        // Register core aliases before optional packs, with a stable namespace order within each group.
        var order = Comparator.<String, Boolean>comparing(id -> !"core".equals(bindings.get(id).pack()))
                .thenComparing(Comparator.naturalOrder());
        Map<String, GraphTypeAdapter> adapters = new TreeMap<>(order);
        Map<String, String> unavailable = new TreeMap<>();
        for (var entry : bindings.entrySet()) {
            String id = entry.getKey();
            try {
                adapters.put(id, entry.getValue().instantiate(id, GraphTypeAdapter.class));
            } catch (IOException e) {
                unavailable.put(id, e.getMessage());
            }
        }
        return new GraphTypeRegistry(adapters, unavailable);
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
        try (var archive = Sha256.stream(urls.getFirst().openStream());
             var stream = new ZipInputStream(archive)) {
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
            return new SkillCatalog(
                    manifest, files, Sha256.finish(archive));
        }
    }
}
