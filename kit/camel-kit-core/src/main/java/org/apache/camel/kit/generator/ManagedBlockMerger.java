package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for merging content into files using managed blocks. Managed blocks are marked with special comments and can
 * be updated without affecting surrounding content.
 */
public class ManagedBlockMerger {

    private static final Pattern MANAGED_BLOCK_PATTERN = Pattern.compile(
            "<!-- CAMEL-KIT-MANAGED-BLOCK-START: (.*?) -->.*?<!-- CAMEL-KIT-MANAGED-BLOCK-END: \\1 -->",
            Pattern.DOTALL);

    /**
     * Merges content into a file using a managed block. If the file doesn't exist, creates it. If the file exists but
     * doesn't have the managed block, appends it. If the managed block exists, replaces it with new content.
     *
     * @param  targetFile  the file to merge into
     * @param  blockName   unique identifier for the managed block
     * @param  content     the content to place in the managed block
     * @throws IOException if file operations fail
     */
    public void mergeBlock(Path targetFile, String blockName, String content) throws IOException {
        String managedBlockStart = "<!-- CAMEL-KIT-MANAGED-BLOCK-START: " + blockName + " -->\n";
        String managedBlockEnd = "\n<!-- CAMEL-KIT-MANAGED-BLOCK-END: " + blockName + " -->";
        String fullManagedBlock = managedBlockStart + content + managedBlockEnd;

        if (!Files.exists(targetFile)) {
            // File doesn't exist, create it with the managed block
            AtomicFileWriter.write(targetFile, fullManagedBlock + "\n");
            return;
        }

        String existingContent = Files.readString(targetFile);
        String patternStr = Pattern.quote(managedBlockStart)
                            + ".*?"
                            + Pattern.quote(managedBlockEnd);
        Pattern blockPattern = Pattern.compile(patternStr, Pattern.DOTALL);
        Matcher matcher = blockPattern.matcher(existingContent);

        if (matcher.find()) {
            // Replace existing managed block
            String updated = matcher.replaceFirst(Matcher.quoteReplacement(fullManagedBlock));
            AtomicFileWriter.write(targetFile, updated);
        } else {
            // Append managed block to existing file
            String updated = existingContent.trim() + "\n\n" + fullManagedBlock + "\n";
            AtomicFileWriter.write(targetFile, updated);
        }
    }
}
