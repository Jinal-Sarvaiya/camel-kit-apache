package org.apache.camel.kit.generator;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.camel.kit.util.ConfinedPaths;

/** Previews a catalog switch and keeps retired assets in place until generation succeeds. */
public final class CatalogUpdate {
    private static final String STAGING_PREFIX = ".camel-kit-catalog-";
    private static final Pattern STAGING_NAME = Pattern.compile(Pattern.quote(STAGING_PREFIX) + "[0-9]+");

    private final InitContext ctx;
    private final Path project;
    private final List<ManagedFiles.Change> retirements;
    private final Set<Path> selected = new HashSet<>();
    private final Set<Path> removable = new HashSet<>();
    private final Set<Path> removableDirs;
    private final Set<Path> shapeChanges = new TreeSet<>();

    CatalogUpdate(InitContext ctx, Set<String> installed, List<ManagedFiles.Change> retired) throws IOException {
        this.ctx = ctx;
        project = ctx.projectDir().toAbsolutePath().normalize();
        retirements = List.copyOf(retired);
        installed.forEach(path -> selected.add(project.resolve(path)));
        retired.stream().filter(change -> change.action() == ManagedFiles.Action.REMOVE)
                .forEach(change -> removable.add(project.resolve(change.path())));
        removableDirs = parents(removable);

        for (Path file : selected) {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                shapeChanges.add(file);
            }
        }
        for (Path dir : parents(selected)) {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                shapeChanges.add(dir);
            }
        }
        for (Path root : shapeChanges) {
            removableTree(root, root);
        }
    }

    private Set<Path> parents(Set<Path> files) {
        Set<Path> dirs = new HashSet<>();
        for (Path file : files) {
            Path dir = file.getParent();
            while (!dir.equals(project) && dirs.add(dir)) {
                dir = dir.getParent();
            }
        }
        return dirs;
    }

    public List<ManagedFiles.Change> retirements() {
        return retirements;
    }

    /** A previous run may already have installed the new layout when removal of its backup failed. */
    public static void checkInterruptedUpdate(Path project, Path skillsDir) throws IOException {
        ConfinedPaths.requirePath(project, skillsDir);
        if (!Files.isDirectory(skillsDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(skillsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (!STAGING_NAME.matcher(dir.getFileName().toString()).matches()) {
                    return FileVisitResult.CONTINUE;
                }
                // A catalog resource always lives below a skill or shared, so init cannot stage at the skills root.
                if (skillsDir.equals(dir.getParent())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try (var entries = Files.newDirectoryStream(dir, "{previous,new}")) {
                    if (entries.iterator().hasNext()) {
                        throw new InvalidAgentConfigurationException(
                                "Unfinished catalog update at " + dir
                                                                     + ". Once init has stopped, review its contents, move it outside the skills directory, and retry.");
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                // Unrelated user skill directories need not be readable to install the selected catalog.
                if (failure instanceof AccessDeniedException denied) {
                    if (!skillsDir.equals(file.getParent())
                            && STAGING_NAME.matcher(file.getFileName().toString()).matches()) {
                        throw InvalidAgentConfigurationException.unreadableDirectory(denied);
                    }
                    return FileVisitResult.CONTINUE;
                }
                if (failure instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw failure;
            }
        });
    }

    private List<Path> removableTree(Path original, Path actual) throws IOException {
        ConfinedPaths.requireTree(project, actual);
        try (var tree = Files.walk(actual)) {
            List<Path> entries = tree.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Path path = original.resolve(actual.relativize(entry));
                boolean owned = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                        ? removableDirs.contains(path)
                        : removable.contains(path) && ctx.files().matchesCatalogFile(ctx.files().key(path), entry);
                if (!owned) {
                    throw new InvalidAgentConfigurationException(
                            "Catalog layout conflicts with existing path: " + path);
                }
                if (!Files.isWritable(entry.getParent())) {
                    throw new InvalidAgentConfigurationException(
                            "Cannot replace catalog layout at " + original
                                                                 + ": directory is not writable: " + entry.getParent());
                }
            }
            return entries;
        }
    }

    /** Only resources changing between a file and a directory need a temporary destination. */
    public void generate(AgentGenerator generator) throws Exception {
        List<Replacement> replacements = new ArrayList<>();
        try (Closeable cleanup = () -> discardStaging(replacements)) {
            Map<Path, Path> outputs = new HashMap<>();
            for (Path root : shapeChanges) {
                ConfinedPaths.requirePath(project, root);
                Path scratch = Files.createTempDirectory(root.getParent(), STAGING_PREFIX);
                var replacement
                        = new Replacement(root, scratch, scratch.resolve("new").resolve(project.relativize(root)));
                replacements.add(replacement);
                for (Path file : selected) {
                    if (file.startsWith(root)) {
                        outputs.put(file, replacement.next().resolve(root.relativize(file)));
                    }
                }
            }
            ctx.files().stageCatalog(outputs);
            generator.generate(ctx);
            try {
                for (Replacement replacement : replacements) {
                    removableTree(replacement.target(), replacement.target());
                    Files.move(replacement.target(), replacement.previous());
                    Files.move(replacement.next(), replacement.target());
                }
            } catch (IOException failure) {
                for (Replacement replacement : replacements.reversed()) {
                    try {
                        replacement.restore();
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                throw failure;
            }
            for (Replacement replacement : replacements) {
                for (Path entry : removableTree(replacement.target(), replacement.previous())) {
                    Files.delete(entry);
                }
                ctx.printer().println("  Replaced catalog layout at " + ctx.files().key(replacement.target()));
            }
        } finally {
            ctx.files().stageCatalog(Map.of());
        }
        ctx.files().retireCatalogFiles(ctx, retirements.stream()
                .filter(change -> shapeChanges.stream().noneMatch(project.resolve(change.path())::startsWith))
                .toList());
    }

    private record Replacement(Path target, Path scratch, Path next) {
        Path previous() {
            return scratch.resolve("previous");
        }

        void restore() throws IOException {
            if (Files.exists(previous(), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(target, next);
                }
                Files.move(previous(), target);
            }
        }
    }

    private void discardStaging(List<Replacement> replacements) throws IOException {
        IOException failure = null;
        for (Replacement replacement : replacements) {
            try {
                Path generated = replacement.scratch().resolve("new");
                ConfinedPaths.requireTree(project, generated);
                if (Files.exists(generated, LinkOption.NOFOLLOW_LINKS)) {
                    List<Path> entries;
                    try (var tree = Files.walk(generated)) {
                        entries = tree.sorted(Comparator.reverseOrder()).toList();
                    }
                    for (Path entry : entries) {
                        Files.delete(entry);
                    }
                }
                // If restoration failed, leave the original in its recovery directory.
                Files.delete(replacement.scratch());
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
