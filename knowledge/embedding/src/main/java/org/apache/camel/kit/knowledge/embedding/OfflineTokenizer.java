package org.apache.camel.kit.knowledge.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

/**
 * Keeps bundled tokenizer initialization local, including DJL native-library discovery. These JVM-wide defaults also
 * affect other DJL/JNA consumers in the standalone Knowledge process.
 */
final class OfflineTokenizer {
    private OfflineTokenizer() {
    }

    static synchronized HuggingFaceTokenizer load(InputStream input, Map<String, String> options) throws IOException {
        // DJL gives environment variables precedence over system properties.
        String offline = System.getenv("DJL_OFFLINE");
        String flavor = System.getenv("RUST_FLAVOR");
        if (offline != null && !Boolean.parseBoolean(offline)) {
            throw new IllegalStateException(
                    "Camel Kit requires DJL_OFFLINE=true; remove the conflicting environment value");
        }
        if (flavor != null && !"cpu".equals(flavor)) {
            throw new IllegalStateException(
                    "Camel Kit requires RUST_FLAVOR=cpu; remove the conflicting environment value");
        }
        System.setProperty("ai.djl.offline", "true");
        System.setProperty("RUST_FLAVOR", "cpu");
        System.setProperty("ai.djl.util.cuda.fork", "false");
        // JNA otherwise runs ldconfig while DJL probes CUDA, even for the bundled CPU tokenizer.
        System.getProperties().putIfAbsent("jna.platform.library.path", "");
        return HuggingFaceTokenizer.newInstance(input, options);
    }
}
