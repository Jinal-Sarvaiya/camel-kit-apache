package org.apache.camel.kit.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects existing files before initializing a Camel-Kit workspace to report what will be preserved, merged, or
 * created.
 */
public class InitConflictDetector {

    /**
     * Checks for existing files that init will interact with.
     *
     * @param  targetDir         the project directory
     * @param  isExistingProject true if pom.xml exists (existing Maven project)
     * @return                   list of existing files that will be interacted with
     */
    public List<ConflictingFile> detectConflicts(Path targetDir, boolean isExistingProject) {
        List<ConflictingFile> existing = new ArrayList<>();

        // Check for files that will be handled during init
        checkFile(existing, targetDir.resolve("AGENTS.md"), ConflictType.AGENT_INSTRUCTIONS);
        checkFile(existing, targetDir.resolve("CLAUDE.md"), ConflictType.AGENT_INSTRUCTIONS);

        // Check for Maven wrapper
        if (isExistingProject) {
            checkFile(existing, targetDir.resolve("mvnw"), ConflictType.MAVEN_WRAPPER);
            checkFile(existing, targetDir.resolve("mvnw.cmd"), ConflictType.MAVEN_WRAPPER);
            checkFile(existing, targetDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
                    ConflictType.MAVEN_WRAPPER);
        }

        // Check for .camel-kit directory
        Path camelKitDir = targetDir.resolve(".camel-kit");
        if (Files.isDirectory(camelKitDir)) {
            checkFile(existing, camelKitDir.resolve("config.properties"), ConflictType.CONFIGURATION);
        }

        return existing;
    }

    private void checkFile(List<ConflictingFile> list, Path file, ConflictType type) {
        if (Files.exists(file)) {
            list.add(new ConflictingFile(file, type));
        }
    }

    public enum ConflictType {
        AGENT_INSTRUCTIONS,
        MAVEN_WRAPPER,
        CONFIGURATION
    }

    public record ConflictingFile(Path path, ConflictType type) {
    }
}
