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
            throw new IOException(
                    key + ": expected a named step and a skill/<id> or provider/<id> handler, got " + handler);
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

    static final String PROTOCOL = """
            Run each required hook from the project root using the Kit distribution that installed this step.
            Write caller-owned input JSON with the selected `target`, all in-scope `sources` and the evidence
            `artifacts` by role. Use project-relative paths for sources and artifacts.
            On `invoke-skill`, open `request.implementation` relative to the host's installed skills directory
            (the parent of this skill's directory). Invoke that skill with the emitted request and submit its
            result with the same command plus `--result <result.json>`.
            On `needs-input`, save the output, ask the developer its question, put their confirmed answer in
            `input.answer`, and repeat the call with `--resume <pending-result.json>` for dispatch and submission.
            Only a zero exit with status `passed` satisfies a hook. Return the outcome to the caller.
            The assistant decides the next ordinary step.
            """.stripTrailing();

    String instructions(String key) {
        return String.format(Locale.ROOT,
                "Required hook: `{COMMAND_PREFIX} hook --id %s --input <input.json>`. Supported targets: %s. "
                                          + "Required checks and evidence roles: %s.",
                key, targets, new TreeMap<>(checks));
    }
}
