package org.apache.camel.kit.graph.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.camel.kit.graph.ProjectGraph;

/** A build-selected migration parser. Its {@code provider/<id>} catalog entry identifies the implementation. */
public interface MigrationParser extends GraphParser {
    int API_VERSION = 1;

    /** The API version this implementation was compiled against. */
    int apiVersion();

    /** Lowercase file extensions without the dot. These candidates count towards the shared source-byte limit. */
    Set<String> inputExtensions();

    /** Optional content check on a size-checked candidate, for formats sharing an extension. */
    default boolean acceptsContent(Path file) {
        return true;
    }

    @Override
    default List<Path> scannedFilePaths(Path projectRoot, List<Path> projectFiles) {
        Set<String> extensions = inputExtensions();
        return GraphParser
                .findFilePaths(projectRoot, projectFiles, file -> GraphParser.matchesExtension(file, extensions))
                .stream().filter(this::acceptsContent).toList();
    }

    private List<Path> inputs(Path root) {
        return scannedFilePaths(root, GraphParser.projectFiles(root, inputExtensions()));
    }

    @Override
    default List<String> scannedFiles(Path root) {
        return GraphParser.relativeFileNames(root, inputs(root));
    }

    @Override
    default void parse(Path root, ProjectGraph graph) {
        parseFiles(root, inputs(root), graph);
    }

    @Override
    default ProjectGraph parseFragment(Path root, ProjectGraph baseGraph) {
        return parseFragment(root, baseGraph, inputs(root));
    }

    /**
     * Project the pack's private source model into the parser-local graph, recording source-relative file properties.
     */
    @Override
    void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph);
}
