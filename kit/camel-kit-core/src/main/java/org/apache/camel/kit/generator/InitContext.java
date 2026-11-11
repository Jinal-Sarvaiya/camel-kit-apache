package org.apache.camel.kit.generator;

import java.nio.file.Path;

import org.apache.camel.kit.config.AgentConfig;
import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.output.Printer;

public record InitContext(
        AgentConfig agent,
        String agentName,
        Path commandsDir,
        Path skillsDir,
        Path projectDir,
        String commandPrefix,
        DistributionConfig distribution,
        boolean withCitrusMcp,
        Printer printer,
        ManagedFiles files) {

    public InitContext(
                       AgentConfig agent,
                       String agentName,
                       Path commandsDir,
                       Path skillsDir,
                       Path projectDir,
                       String commandPrefix,
                       DistributionConfig distribution,
                       boolean withCitrusMcp,
                       Printer printer) {
        this(
             agent,
             agentName,
             commandsDir,
             skillsDir,
             projectDir,
             commandPrefix,
             distribution,
             withCitrusMcp,
             printer,
             ManagedFiles.unrestricted(projectDir));
    }

    public InitContext(
                       AgentConfig agent,
                       String agentName,
                       Path commandsDir,
                       Path skillsDir,
                       Path projectDir,
                       String commandPrefix,
                       Printer printer) {
        this(
             agent,
             agentName,
             commandsDir,
             skillsDir,
             projectDir,
             commandPrefix,
             DistributionConfig.loadFromClasspathOrDefaults(),
             false,
             printer);
    }
}
