package org.apache.camel.kit.workflow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.camel.kit.doc.FrontmatterHandler;
import org.apache.camel.kit.util.AtomicFileWriter;
import org.apache.camel.kit.workflow.SkillPack.Declaration;
import org.apache.camel.kit.workflow.SkillPack.Entry;
import org.apache.camel.kit.workflow.WorkflowManifest.WorkflowCommand;
import org.apache.camel.kit.workflow.WorkflowManifest.WorkflowSkill;

import com.fasterxml.jackson.databind.JsonNode;

/** Maven entry point. Pack selection and resource lookup happen before packaging, never in the user's workspace. */
public final class SkillPackCompiler {

    private static final Pattern GUIDE_REFERENCE = Pattern.compile("(?<![A-Za-z0-9_.-])((?:(?:"
                                                                   + SkillPack.ID
                                                                   + "/)?guides/|shared/)[A-Za-z0-9_./-]+?\\.md|"
                                                                   + SkillPack.ID
                                                                   + "/SKILL\\.md)(?![A-Za-z0-9_/-]|\\.[A-Za-z0-9])");
    private static final Pattern STEP_MARKER = Pattern.compile(
            "(?m)^<!-- camel-kit:step (" + SkillPack.ID + ") -->(?=\\r?$)((?:\\r?\\n){0,2})");

    private final ClassLoader loader;
    private final Map<Declaration, byte[]> resources = new HashMap<>();
    private final Map<String, Declaration> base = new TreeMap<>();

    private SkillPackCompiler(ClassLoader loader) {
        this.loader = loader;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: SkillPackCompiler <output-catalog.zip> [pack-id,...]");
        }
        List<String> packs = args.length == 1 || args[1].isBlank() ? List.of() : Arrays.asList(args[1].split(",", -1));
        Path output = Path.of(args[0]).toAbsolutePath();
        try {
            writeCatalog(output, compile(SkillPackCompiler.class.getClassLoader(), packs));
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static void writeCatalog(Path output, byte[] bytes) throws IOException {
        try {
            Files.createDirectories(output.getParent());
            AtomicFileWriter.write(output, bytes);
        } catch (IOException e) {
            throw new IOException("Cannot write skill catalog " + output + " (" + e + ")", e);
        }
    }

    static byte[] compile(ClassLoader loader, List<String> selected) throws Exception {
        List<String> ids = new ArrayList<>(List.of("core"));
        ids.addAll(selected);
        Map<String, SkillPack> packs = SkillPack.load(loader, ids);
        return new SkillPackCompiler(loader).assemble(packs);
    }

    private byte[] assemble(Map<String, SkillPack> packs) throws Exception {
        WorkflowManifest workflow = readCore();
        for (SkillPack pack : packs.values()) {
            readDeclarations(pack.id(), pack.add());
            readDeclarations(pack.id(), pack.replace());
        }
        Map<String, Declaration> definitions = SkillPack.definitions(packs, base);
        Map<String, Declaration> entries = SkillPack.resolve(packs, definitions, workflow);
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, SkillCatalog.Binding> bindings = new TreeMap<>();
        Map<String, List<String>> steps = new TreeMap<>();
        Map<String, String> stageSkills = new HashMap<>();
        workflow.stages().forEach(stage -> stageSkills.put(stage.id(), stage.skill()));
        for (Declaration d : entries.values()) {
            String path;
            switch (d.kind()) {
                case "skill", "resource" -> {
                    path = d.kind().equals("skill") ? d.name() + "/SKILL.md" : d.name();
                    files.put(path, resources.get(d));
                }
                case "step" -> {
                    String[] parts = d.name().split("/");
                    String skill = stageSkills.get(parts[0]);
                    if (skill == null) {
                        throw d.error("unknown workflow stage " + parts[0]);
                    }
                    path = skill + "/SKILL.md";
                    steps.computeIfAbsent(parts[0] + "/" + parts[1], unused -> new ArrayList<>())
                            .add(d.entry().hook() == null
                                    ? new String(resources.get(d), StandardCharsets.UTF_8).strip()
                                    : d.entry().hook().instructions(d.key()));
                }
                case "provider" -> path = null;
                default -> throw d.error("unknown contribution kind");
            }
            List<String> requires = requirements(d, path, definitions);
            for (String key : requires) {
                SkillPack.requireContribution(d, key, entries, packs);
            }
            bindings.put(d.key(),
                    new SkillCatalog.Binding(
                            d.pack(), d.entry().contract(), requires, path, d.entry().provider(), d.entry().hook(),
                            d.entry().handles()));
        }
        WorkflowManifest effective = composeWorkflow(workflow, entries, definitions, steps);
        bindSteps(stageSkills, files, steps, entries);
        var manifest = new SkillCatalog.Manifest(1, List.copyOf(packs.keySet()), effective, bindings);
        return archive(new SkillCatalog(manifest, files, null));
    }

    private List<String> requirements(Declaration declaration, String path, Map<String, Declaration> definitions) {
        var requires = new TreeSet<>(declaration.entry().requires());
        if (declaration.entry().hook() != null) {
            requires.add(declaration.entry().hook().handler());
        } else if (path != null && path.endsWith(".md")) {
            String owner = path.substring(0, path.indexOf('/'));
            Matcher links = GUIDE_REFERENCE.matcher(new String(resources.get(declaration), StandardCharsets.UTF_8));
            while (links.find()) {
                String link = links.group(1);
                String target = link.startsWith("guides/") ? owner + "/" + link : link;
                String key = target.endsWith("/SKILL.md")
                        ? "skill/" + target.substring(0, target.indexOf('/'))
                        : "resource/" + target;
                // Only declared destinations denote Kit assets. Examples may also name project files.
                if (definitions.containsKey(key) && !key.equals(declaration.key())) {
                    requires.add(key);
                }
            }
        }
        return List.copyOf(requires);
    }

    private WorkflowManifest composeWorkflow(
            WorkflowManifest workflow, Map<String, Declaration> entries,
            Map<String, Declaration> definitions, Map<String, List<String>> steps)
            throws IOException {
        List<WorkflowSkill> skills = new ArrayList<>();
        List<WorkflowCommand> commands = new ArrayList<>(
                workflow.commands().stream()
                        .filter(c -> entries.containsKey("skill/" + c.skill())).toList());
        for (WorkflowSkill skill : workflow.skills()) {
            Declaration d = entries.get("skill/" + skill.name());
            if (d != null) {
                if (skillHeader(d).get("user_invocable").booleanValue() != skill.userInvocable()) {
                    throw d.error("user_invocable differs from the core skill contract");
                }
                skills.add(new WorkflowSkill(
                        skill.name(), skill.userInvocable(), skill.status(), skill.generatedCommand(),
                        d.entry().requires()));
            }
        }
        List<String> routes = new ArrayList<>();
        for (Declaration d : entries.values()) {
            if (!d.kind().equals("skill") || base.containsKey(d.key())) {
                continue;
            }
            JsonNode header = skillHeader(d);
            boolean visible = header.get("user_invocable").booleanValue();
            Declaration original = definitions.get(d.key());
            if (!d.pack().equals(original.pack())
                    && visible != skillHeader(original).get("user_invocable").booleanValue()) {
                throw d.error("replacement must preserve visibility declared by " + original.location());
            }
            skills.add(new WorkflowSkill(
                    d.name(), visible, visible ? "standalone" : "internal", visible ? d.name() : null,
                    d.entry().requires()));
            if (visible) {
                String description = header.get("description").textValue();
                commands.add(new WorkflowCommand(
                        d.name(), List.of(), d.name(), true, true, "utility", description, true, false, List.of()));
                routes.add("- /" + d.name() + " — " + description);
            }
        }
        if (!routes.isEmpty()) {
            steps.computeIfAbsent("route/choose", unused -> new ArrayList<>()).add(
                    "For a matching request, invoke the corresponding installed skill:\n\n"
                                                                                   + String.join("\n", routes));
        }
        var effective = new WorkflowManifest(
                workflow.version(), workflow.description(), commands, skills,
                workflow.stages(), workflow.artifacts(), workflow.mcpServers(), workflow.websiteReference());
        WorkflowManifestValidator.validateOrThrow(effective);
        return effective;
    }

    private WorkflowManifest readCore() throws Exception {
        URL source = SkillPack.uniqueResource(loader, WorkflowManifestLoader.DEFAULT_RESOURCE);
        WorkflowManifest workflow;
        try (var in = source.openStream()) {
            workflow = WorkflowManifestLoader.load(in);
        }
        if (!"1.0".equals(workflow.version())) {
            throw new IOException("Unsupported core workflow contract " + workflow.version() + " in " + source);
        }
        if (source.openConnection() instanceof JarURLConnection jar) {
            try (var fs = FileSystems.newFileSystem(Path.of(jar.getJarFileURL().toURI()))) {
                readCoreFiles(fs.getPath("/skills"), workflow);
            }
        } else {
            readCoreFiles(Path.of(source.toURI()).getParent().resolve("../skills").normalize(), workflow);
        }
        return workflow;
    }

    private void readCoreFiles(Path root, WorkflowManifest workflow) throws IOException {
        Map<String, WorkflowSkill> expected = new TreeMap<>();
        workflow.skills().forEach(skill -> expected.put(skill.name(), skill));
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                String key = path.matches(SkillPack.ID + "/SKILL\\.md")
                        ? "skill/" + path.substring(0, path.indexOf('/')) : "resource/" + path;
                List<String> requires = List.of();
                if (key.startsWith("skill/")) {
                    WorkflowSkill skill = expected.remove(key.substring("skill/".length()));
                    if (skill == null) {
                        throw new IOException("Undeclared core " + key + " in " + path);
                    }
                    requires = skill.requires();
                }
                var d = new Declaration("core", key, new Entry(1, "skills/" + path, null, requires, null, List.of()));
                base.put(key, d);
                resources.put(d, Files.readAllBytes(file));
            }
        }
        if (!expected.isEmpty()) {
            throw new IOException("Core workflow skills missing SKILL.md: " + expected.keySet());
        }
    }

    private void readDeclarations(String pack, Map<String, Entry> entries) throws IOException {
        for (var item : new TreeMap<>(entries).entrySet()) {
            var d = new Declaration(pack, item.getKey(), item.getValue());
            Entry e = d.entry();
            if (e.contract() != 1) {
                throw d.error("unsupported contribution contract " + e.contract() + " (expected 1)");
            }
            if (e.hook() != null) {
                e.hook().validate(d.key());
                if (e.resource() != null || e.provider() != null) {
                    throw d.error("hook takes the place of step instruction resources");
                }
                continue;
            }
            String id = SkillPack.ID;
            if (d.key().matches("provider/" + id)) {
                if (e.provider() == null || e.resource() != null) {
                    throw d.error("expected a provider with service and implementation");
                }
                validateProvider(d);
                continue;
            }
            boolean file = d.key().startsWith("resource/") && SkillCatalog.safePath(d.key())
                    && d.key().substring("resource/".length()).contains("/") && !d.key().endsWith("/SKILL.md");
            if (!file && !d.key().matches("skill/" + id) && !d.key().matches("step/" + id + "/" + id + "/" + id)) {
                throw d.error("invalid contribution identifier");
            }
            if (d.key().equals("skill/shared")) {
                throw d.error("reserved skill name 'shared' is used for shared resources");
            }
            if (!SkillCatalog.safePath(e.resource()) || e.provider() != null) {
                throw d.error("expected a pack-relative resource");
            }
            String path = SkillPack.directory(pack) + e.resource();
            try {
                URL url = SkillPack.uniqueResource(loader, path);
                if ("file".equals(url.getProtocol()) && !Files.isRegularFile(Path.of(url.toURI()))
                        || url.openConnection() instanceof JarURLConnection jar && jar.getJarEntry().isDirectory()) {
                    throw new IOException("expected a regular file: " + path);
                }
                byte[] bytes;
                try (var in = url.openStream()) {
                    bytes = in.readAllBytes();
                }
                if (d.key().startsWith("step/") && new String(bytes, StandardCharsets.UTF_8).isBlank()) {
                    throw new IOException("empty workflow step instructions");
                }
                resources.put(d, bytes);
            } catch (IOException | java.net.URISyntaxException ex) {
                throw d.error(ex.getMessage());
            }
            if (d.kind().equals("skill")) {
                JsonNode header = skillHeader(d);
                String text = new String(resources.get(d), StandardCharsets.UTF_8);
                String yaml = FrontmatterHandler.extractFrontmatterYaml(text);
                // Host metadata adapters insert their visibility flags next to a top-level block key.
                if (!Pattern.compile("(?m)^user_invocable: *(true|false) *(#.*)?$").matcher(yaml).find()) {
                    String normalized = SkillPack.YAML.writeValueAsString(header) + "---\n"
                                        + FrontmatterHandler.extractBody(text);
                    resources.put(d, normalized.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private JsonNode skillHeader(Declaration d) throws IOException {
        String yaml = FrontmatterHandler.extractFrontmatterYaml(new String(resources.get(d), StandardCharsets.UTF_8));
        JsonNode header;
        try {
            header = yaml == null ? null : SkillPack.YAML.readTree(yaml);
        } catch (IOException e) {
            throw d.error(e.getMessage());
        }
        if (header == null || !d.name().equals(header.path("name").asText())
                || !header.path("user_invocable").isBoolean()
                || !header.path("description").isTextual() || header.get("description").textValue().isBlank()) {
            throw d.error("SKILL.md requires matching name, description and boolean user_invocable");
        }
        if (header.has("user-invocable") || header.has("disable-model-invocation")
                || header.path("metadata").has("user-invocable")) {
            throw d.error("declare user_invocable only; Kit generates host-specific visibility");
        }
        return header;
    }

    private void validateProvider(Declaration d) throws IOException {
        var provider = d.entry().provider();
        try {
            if (provider.service() == null || provider.implementation() == null) {
                throw d.error("provider requires service and implementation");
            }
            try {
                SkillPack.uniqueResource(loader, provider.service().replace('.', '/') + ".class");
                SkillPack.uniqueResource(loader, provider.implementation().replace('.', '/') + ".class");
            } catch (IOException e) {
                throw d.error("invalid provider " + provider.implementation() + ": " + e.getMessage());
            }
            Class<?> service = Class.forName(provider.service(), false, loader);
            Class<?> impl = Class.forName(provider.implementation(), false, loader);
            if (!service.isInterface() || !Modifier.isPublic(service.getModifiers()) || !service.isAssignableFrom(impl)
                    || !Modifier.isPublic(impl.getModifiers()) || Modifier.isAbstract(impl.getModifiers())) {
                throw d.error("expected a public concrete implementation of " + provider.service() + ": "
                              + provider.implementation());
            }
            impl.getConstructor();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw d.error("invalid provider " + provider + ": " + e);
        }
    }

    private static void bindSteps(
            Map<String, String> stageSkills, Map<String, byte[]> files, Map<String, List<String>> steps,
            Map<String, Declaration> entries)
            throws IOException {
        var bound = new HashSet<String>();
        for (var stage : stageSkills.entrySet()) {
            String path = stage.getValue() + "/SKILL.md";
            Matcher marker = STEP_MARKER.matcher(new String(files.get(path), StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            while (marker.find()) {
                String point = stage.getKey() + "/" + marker.group(1);
                List<String> instructions = steps.get(point);
                if (instructions != null && !bound.add(point)) {
                    throw stepError(point, path, entries);
                }
                String replacement = instructions == null ? "" : String.join("\n\n", instructions) + marker.group(2);
                marker.appendReplacement(content, Matcher.quoteReplacement(replacement));
            }
            files.put(path, marker.appendTail(content).toString().getBytes(StandardCharsets.UTF_8));
        }
        for (String point : steps.keySet()) {
            if (!bound.contains(point)) {
                throw stepError(point, stageSkills.get(point.split("/")[0]) + "/SKILL.md", entries);
            }
        }
    }

    private static IOException stepError(String point, String path, Map<String, Declaration> entries) {
        var declarations = entries.values().stream().filter(d -> d.key().startsWith("step/" + point + "/"))
                .map(Declaration::location).toList();
        Declaration skill = entries.get("skill/" + path.substring(0, path.indexOf('/')));
        return skill.error("expected one <!-- camel-kit:step " + point.substring(point.indexOf('/') + 1)
                           + " --> marker for " + point + " in " + path
                           + (declarations.isEmpty() ? "" : " required by " + String.join(", ", declarations)));
    }

    private static byte[] archive(SkillCatalog catalog) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            writeEntry(zip, SkillCatalog.MANIFEST, SkillCatalog.JSON.writeValueAsBytes(catalog.manifest()));
            for (String path : catalog.paths()) {
                writeEntry(zip, "skills/" + path, catalog.open(path).readAllBytes());
            }
        }
        return buffer.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0));
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
