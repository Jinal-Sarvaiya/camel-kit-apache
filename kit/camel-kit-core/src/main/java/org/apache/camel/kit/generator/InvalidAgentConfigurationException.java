package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Signals invalid existing agent configuration that must be reported without starting generation.
 */
public final class InvalidAgentConfigurationException extends IOException {

    public InvalidAgentConfigurationException(String message) {
        super(message);
    }

    public InvalidAgentConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public static InvalidAgentConfigurationException unreadableDirectory(AccessDeniedException cause) {
        String path = cause.getFile();
        Path parent = path == null ? null : Path.of(path).getParent();
        // Listing a directory without search permission can fail on the first child's attributes.
        if (parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isExecutable(parent)) {
            path = parent.toString();
        }
        return new InvalidAgentConfigurationException(
                "Cannot inspect " + path + ". Restore read and search permissions and retry.", cause);
    }
}
