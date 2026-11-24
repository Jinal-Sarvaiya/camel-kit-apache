package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.camel.kit.workflow.WorkflowHookProvider.Check;
import org.apache.camel.kit.workflow.WorkflowHookProvider.Decision;
import org.apache.camel.kit.workflow.WorkflowHookProvider.Request;
import org.apache.camel.kit.workflow.WorkflowHookProvider.Result;
import org.apache.camel.kit.workflow.WorkflowHookProvider.Revision;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;

/** Stateless dispatch. Only the distribution selects executable code. The caller owns the inputs and sequencing. */
public final class WorkflowHookRunner {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Textual, config -> {
                for (var shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float,
                        CoercionInputShape.Boolean)) {
                    config.setCoercion(shape, CoercionAction.Fail);
                }
            }).build();

    private record Input(List<String> sources, String target, Map<String, String> artifacts, String answer) {
    }

    public static String toJson(Result result) throws IOException {
        return JSON.writeValueAsString(result);
    }

    private final SkillCatalog catalog;
    private final Path project;

    public WorkflowHookRunner(SkillCatalog catalog, Path project) throws IOException {
        this.catalog = catalog;
        this.project = project.toRealPath();
    }

    public Result run(String id, Path inputFile, Path submission, Path resume) throws Exception {
        var entries = catalog.manifest().contributions();
        var step = entries.get(id);
        if (step == null || step.hook() == null) {
            throw new IOException("No executable hook declared for this contribution");
        }
        var handler = entries.get(step.hook().handler());
        var provider = handler.provider();
        if (provider != null && submission != null) {
            throw new IOException("requires Java dispatch to " + step.hook().handler());
        }
        Result result;
        if (submission != null) {
            result = read(submission, Result.class);
        } else {
            Request request = request(id, step, handler, inputFile, resume);
            if (provider == null) {
                return new Result(request, "invoke-skill", Map.of(), null);
            }
            var type = Class.forName(provider.implementation(), false, WorkflowHookProvider.class.getClassLoader())
                    .asSubclass(WorkflowHookProvider.class);
            result = type.getConstructor().newInstance().invoke(project, request);
            verifyRequest(request, result);
        }
        // Verify the caller's current inputs after reading the submission or executing bundled code.
        Request expected = request(id, step, handler, inputFile, resume);
        verifyRequest(expected, result);
        if ("needs-input".equals(result.status())) {
            requirePending(result);
        } else {
            if (!"passed".equals(result.status()) && !"failed".equals(result.status()) || result.question() != null) {
                throw new IOException("expected passed, failed or needs-input result");
            }
            if (!expected.checks().keySet().containsAll(result.checks().keySet())) {
                throw new IOException("undeclared checks in " + result.checks().keySet());
            }
            boolean failed = false;
            for (String name : new TreeMap<>(expected.checks()).keySet()) {
                Check check = result.checks().get(name);
                if (check == null || check.passed() == null || blank(check.evidence())) {
                    throw new IOException("missing check or evidence for " + name);
                }
                failed |= !check.passed();
                if (!check.passed() && "passed".equals(result.status())) {
                    throw new IOException("check " + name + " failed: " + check.evidence());
                }
            }
            if (!failed && "failed".equals(result.status())) {
                throw new IOException("provider reported failure with all checks passed");
            }
        }
        return result;
    }

    private static void verifyRequest(Request expected, Result result) throws IOException {
        if (result == null || result.request() == null) {
            throw new IOException("Missing result request");
        }
        if (!expected.equals(result.request())) {
            ObjectNode current = JSON.valueToTree(expected);
            ObjectNode submitted = JSON.valueToTree(result.request());
            var differences = current.properties().stream()
                    .filter(field -> !field.getValue().equals(submitted.get(field.getKey())))
                    .map(Map.Entry::getKey).sorted().toList();
            throw new IOException("Stale or mismatched result request fields: " + differences);
        }
    }

    private Request request(String id, SkillCatalog.Binding step, SkillCatalog.Binding handler, Path file, Path resume)
            throws IOException {
        Input input = read(file, Input.class);
        WorkflowHook hook = step.hook();
        if (input.target() == null || !hook.targets().contains(input.target())) {
            throw new IOException(
                    "unsupported target " + input.target() + ", expected one of " + hook.targets());
        }
        if (input.sources() == null || input.sources().isEmpty()) {
            throw new IOException("sources must name the files in scope");
        }
        if (input.answer() != null && input.answer().isBlank()) {
            throw new IOException("confirmed answer must be nonblank");
        }
        var roles = Set.copyOf(hook.checks().values());
        if (input.artifacts() == null || !roles.equals(input.artifacts().keySet())) {
            throw new IOException(
                    "expected artifact roles " + roles + ", received "
                                  + (input.artifacts() == null ? "none" : input.artifacts().keySet()));
        }
        Map<String, String> sources = new HashMap<>();
        for (String source : input.sources()) {
            Revision revision = revision("source", source);
            if (sources.put(source, revision.sha256()) != null) {
                throw new IOException("Duplicate source path: " + source);
            }
        }
        Map<String, Revision> artifacts = new HashMap<>();
        for (var artifact : input.artifacts().entrySet()) {
            artifacts.put(artifact.getKey(), revision(artifact.getKey(), artifact.getValue()));
        }
        String implementation = handler.provider() == null ? handler.path() : handler.provider().implementation();
        Request current = new Request(
                step.contract(), catalog.archiveSha256(), id, step.pack(), hook.handler(), handler.pack(),
                implementation,
                input.target(), sources, artifacts, hook.checks(), List.of());
        if (resume == null && input.answer() == null) {
            return current;
        }
        if (resume == null || blank(input.answer())) {
            throw new IOException("A confirmed answer and --resume <pending-result.json> must be supplied together");
        }
        Result pending = read(resume, Result.class);
        requirePending(pending);
        var previous = pending.request().decisions();
        verifyRequest(current.withDecisions(previous), pending);
        var decisions = new ArrayList<>(previous);
        decisions.add(new Decision(pending.question(), input.answer()));
        return current.withDecisions(decisions);
    }

    private static void requirePending(Result result) throws IOException {
        if (!"needs-input".equals(result.status()) || result.request() == null
                || blank(result.question()) || !result.checks().isEmpty()) {
            throw new IOException(
                    "Expected a needs-input result with the original request, a question and no completed checks");
        }
    }

    private Revision revision(String role, String name) throws IOException {
        if (blank(name) || name.contains("\\") || name.contains(":") || name.startsWith("/")
                || Arrays.stream(name.split("/", -1))
                        .anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            throw new IOException(role + ": expected a project-relative file path, got " + name);
        }
        Path path = project.resolve(name);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    role + ": file not found: " + name + " (project root: " + project
                                  + "; run the hook from the project root)");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())) {
            throw new IOException(role + ": expected a regular project file without symlinks: " + name);
        }
        try (var stream = Sha256.stream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
            return new Revision(name, Sha256.finish(stream));
        }
    }

    private static <T> T read(Path file, Class<T> type) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            T value = JSON.readValue(stream, type);
            if (value == null) {
                throw new IOException("expected a JSON object");
            }
            return value;
        } catch (ValueInstantiationException e) {
            Throwable cause = e.getCause();
            throw new IOException(file + ": " + (cause == null ? e.getOriginalMessage() : cause.getMessage()), e);
        } catch (NoSuchFileException e) {
            throw new IOException("File not found: " + file, e);
        } catch (IOException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
