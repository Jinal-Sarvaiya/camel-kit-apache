package org.apache.camel.kit.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.camel.kit.workflow.SkillCatalog;
import org.apache.camel.kit.workflow.WorkflowManifest;

/**
 * Expected generated workspace contract used by {@link DoctorService}.
 */
public final class DoctorExpectations {

    private final List<WorkflowManifest.WorkflowCommand> generatedStubs;
    private final List<String> requiredSkills;
    private final List<String> publicSkills;
    private final List<String> resources;
    private final Set<String> camelMcpTools;
    private final Set<String> knowledgeMcpTools;
    private final Set<String> citrusMcpTools;

    private DoctorExpectations(
                               List<WorkflowManifest.WorkflowCommand> generatedStubs,
                               List<String> requiredSkills,
                               List<String> publicSkills,
                               List<String> resources,
                               Set<String> camelMcpTools,
                               Set<String> knowledgeMcpTools,
                               Set<String> citrusMcpTools) {
        this.generatedStubs = List.copyOf(generatedStubs);
        this.requiredSkills = List.copyOf(requiredSkills);
        this.publicSkills = List.copyOf(publicSkills);
        this.resources = List.copyOf(resources);
        this.camelMcpTools = Collections.unmodifiableSet(new LinkedHashSet<>(camelMcpTools));
        this.knowledgeMcpTools = Collections.unmodifiableSet(new LinkedHashSet<>(knowledgeMcpTools));
        this.citrusMcpTools = Collections.unmodifiableSet(new LinkedHashSet<>(citrusMcpTools));
    }

    public static DoctorExpectations loadDefault() {
        try {
            SkillCatalog catalog = SkillCatalog.bundled();
            return from(catalog.manifest().workflow(),
                    catalog.paths().stream().filter(p -> !p.endsWith("/SKILL.md")).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load workflow manifest for doctor expectations", e);
        }
    }

    public static DoctorExpectations from(WorkflowManifest workflow) {
        return from(workflow, List.of());
    }

    private static DoctorExpectations from(WorkflowManifest workflow, List<String> resources) {
        return new DoctorExpectations(
                workflow.generatedCommandStubs(),
                workflow.skills().stream()
                        .map(WorkflowManifest.WorkflowSkill::name)
                        .toList(),
                workflow.commands().stream()
                        .filter(WorkflowManifest.WorkflowCommand::userFacing)
                        .map(WorkflowManifest.WorkflowCommand::skill)
                        .distinct()
                        .toList(),
                resources,
                orderedSet(workflow.mcpServer("camel").allowedTools()),
                orderedSet(workflow.mcpServer("camel-knowledge").allowedTools()),
                orderedSet(workflow.mcpServer("citrus").allowedTools()));
    }

    public List<String> userCommands() {
        return generatedStubs.stream()
                .map(WorkflowManifest.WorkflowCommand::name)
                .toList();
    }

    /** Command stubs expected for the given agent, honoring the manifest's skill-only carve-outs. */
    public List<String> userCommands(String agentKey) {
        return generatedStubs.stream()
                .filter(command -> !command.isSkillOnly(agentKey))
                .map(WorkflowManifest.WorkflowCommand::name)
                .toList();
    }

    public List<String> requiredSkills() {
        return requiredSkills;
    }

    public List<String> resources() {
        return resources;
    }

    public List<String> publicSkills() {
        return publicSkills;
    }

    public Set<String> camelMcpTools() {
        return camelMcpTools;
    }

    public Set<String> knowledgeMcpTools() {
        return knowledgeMcpTools;
    }

    public Set<String> citrusMcpTools() {
        return citrusMcpTools;
    }

    private static Set<String> orderedSet(List<String> values) {
        return new LinkedHashSet<>(values);
    }
}
