package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** A bundled Java implementation of the same contract used for assistant-run skill submissions. */
@FunctionalInterface
public interface WorkflowHookProvider {
    Result invoke(Path project, Request request) throws Exception;

    /** SHA-256 covers the exact bytes at the project-relative path. */
    record Revision(String path, String sha256) {
        static DigestInputStream hashing(InputStream input) {
            try {
                return new DigestInputStream(input, MessageDigest.getInstance("SHA-256"));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        static String finishDigest(DigestInputStream input) throws IOException {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        }
    }

    record Decision(String question, String answer) {
        public Decision {
            if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("A decision requires a question and its confirmed answer");
            }
        }
    }

    record Request(int contract, String catalogSha256, String contribution, String pack, String handler,
            String handlerPack,
            String implementation, String target, Map<String, String> sources, Map<String, Revision> artifacts,
            Map<String, String> checks, List<Decision> decisions) {
        public Request {
            sources = Map.copyOf(sources);
            artifacts = Map.copyOf(artifacts);
            checks = Map.copyOf(checks);
            decisions = List.copyOf(decisions);
        }

        Request withDecisions(List<Decision> confirmed) {
            return new Request(
                    contract, catalogSha256, contribution, pack, handler, handlerPack, implementation, target, sources,
                    artifacts,
                    checks, confirmed);
        }
    }

    /** The provider's observation for the artifact role assigned to this check in the request. */
    record Check(Boolean passed, String evidence) {
    }

    /** Echo the request. Use passed, failed or needs-input. Only needs-input carries a question. */
    record Result(Request request, String status, Map<String, Check> checks, String question) {
        public Result {
            checks = checks == null ? Map.of() : Map.copyOf(checks);
        }
    }
}
