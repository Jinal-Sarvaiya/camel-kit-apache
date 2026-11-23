package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Contract 1 for a required check at a named assistant-led workflow step. */
public record WorkflowHook(String handler, List<String> targets, Map<String, String> checks) {
    static final String STEP = "step/" + SkillPack.ID + "/" + SkillPack.ID + "/" + SkillPack.ID;

    public WorkflowHook {
        targets = targets == null ? List.of() : List.copyOf(targets);
        checks = checks == null ? Map.of() : Map.copyOf(checks);
    }

    void validate(String key) throws IOException {
        if (!key.matches(STEP)
                || handler == null || !handler.matches("(skill|provider)/" + SkillPack.ID)) {
            throw new IOException(key + ": hook requires one skill/<id> or provider/<id> handler at a named step");
        }
        if (targets.isEmpty() || targets.stream().anyMatch(t -> !t.matches(SkillPack.ID))
                || Set.copyOf(targets).size() != targets.size()) {
            throw new IOException(key + ": declare distinct supported target IDs");
        }
        if (checks.isEmpty() || checks.entrySet().stream().anyMatch(c -> !c.getKey().matches(SkillPack.ID)
                || !c.getValue().matches(SkillPack.ID))) {
            throw new IOException(key + ": declare required check IDs and their evidence artifact roles");
        }
    }

    boolean supports(String key, SkillCatalog.Binding binding, Set<String> skills) {
        if (binding == null || !binding.handles().contains(key)) {
            return false;
        }
        if (handler.startsWith("skill/")) {
            String skill = handler.substring("skill/".length());
            return skills.contains(skill) && (skill + "/SKILL.md").equals(binding.path()) && binding.provider() == null;
        }
        return binding.provider() != null && WorkflowHookProvider.class.getName().equals(binding.provider().service());
    }

    String instructions(String key) {
        return String.format(Locale.ROOT,
                """
                        Before completing this stage, run the required hook `%s` from the project root.
                        Write caller-owned input JSON with `sources` (all in-scope source file paths), `target`
                        (the selected target, one of %s), and `artifacts` (workspace-relative files by role).
                        The required checks and their evidence roles are %s. Use the Kit distribution that installed this step.
                        Run `{COMMAND_PREFIX} hook --id %s --input <input.json>`. On `invoke-skill`, invoke the named installed skill
                        with the emitted request. Submit its result with the same command plus `--result <result.json>`.
                        On `needs-input`, save that output, ask the developer its question, put their confirmed answer in
                        `input.answer`, and repeat the call with `--resume <pending-result.json>` for dispatch and submission.
                        Only a zero exit with status `passed` satisfies this hook. Return the outcome to the caller.
                        The assistant decides the next ordinary step.
                        """,
                key, targets, new TreeMap<>(checks), key).stripTrailing();
    }
}
