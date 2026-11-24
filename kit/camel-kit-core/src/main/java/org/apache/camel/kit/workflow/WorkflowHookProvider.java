package org.apache.camel.kit.workflow;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A bundled Java implementation of the same contract used for assistant-run skill submissions. */
@FunctionalInterface
public interface WorkflowHookProvider {
    Result invoke(Path project, Request request) throws Exception;

    /** SHA-256 covers the exact bytes at the project-relative path. */
    record Revision(String path, String sha256) {
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
            sources = copyFields("request.sources", sources);
            artifacts = copyFields("request.artifacts", artifacts);
            checks = copyFields("request.checks", checks);
            Objects.requireNonNull(decisions, "request.decisions is required");
            for (int i = 0; i < decisions.size(); i++) {
                Objects.requireNonNull(decisions.get(i), "request.decisions[" + i + "] is null");
            }
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
            checks = checks == null ? Map.of() : copyFields("checks", checks);
        }
    }

    private static <T> Map<String, T> copyFields(String field, Map<String, T> values) {
        Objects.requireNonNull(values, field + " is required");
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, field + " contains a null key");
            Objects.requireNonNull(value, () -> field + "." + key + " is null");
        });
        return Map.copyOf(values);
    }
}
