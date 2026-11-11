package org.apache.camel.kit.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.apache.camel.kit.config.AgentConfig;
import org.apache.camel.kit.generator.ManagedFiles;

/**
 * Result of initializing a Camel-Kit workspace.
 */
public record InitResult(
        String projectName,
        String agentName,
        AgentConfig agent,
        Path targetDir,
        Path camelKitDir,
        Path commandsDir,
        Path skillsDir,
        String citrusVersion,
        int citrusSchemaCount,
        String mavenWrapperVersion,
        InitGraphSummary graph,
        List<Path> createdPaths,
        List<InitWarning> warnings,
        List<ManagedFiles.Change> conflicts) {

    public InitResult {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(agentName, "agentName");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(targetDir, "targetDir");
        Objects.requireNonNull(camelKitDir, "camelKitDir");
        Objects.requireNonNull(commandsDir, "commandsDir");
        Objects.requireNonNull(skillsDir, "skillsDir");
        Objects.requireNonNull(citrusVersion, "citrusVersion");
        Objects.requireNonNull(mavenWrapperVersion, "mavenWrapperVersion");
        createdPaths = List.copyOf(Objects.requireNonNull(createdPaths, "createdPaths"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
    }
}
