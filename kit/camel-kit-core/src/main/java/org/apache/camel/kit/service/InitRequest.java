package org.apache.camel.kit.service;

import java.nio.file.Path;
import java.util.Objects;

import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.output.Printer;

/**
 * Request for initializing a Camel-Kit workspace.
 *
 * <p>
 * {@code recordBaseline} is opt-in: when set, init runs the existing project's tests once to record a baseline result.
 * It is off by default because that executes project build and test code.
 */
public record InitRequest(
        String projectName,
        String agentName,
        Path targetDir,
        String citrusVersion,
        boolean noFetch,
        boolean withCitrusMcp,
        String sourcePlatform,
        String commandPrefix,
        String defaultCitrusVersion,
        DistributionConfig distribution,
        Printer printer,
        InitProgress progress,
        InitReporter reporter,
        boolean dryRun,
        boolean replaceWrapper,
        boolean recordBaseline) {

    /**
     * Creates a request with default flags (no dry-run, no wrapper replacement, no baseline recording). Used by tests
     * that predate the new flags.
     */
    public InitRequest(
                       String projectName,
                       String agentName,
                       Path targetDir,
                       String citrusVersion,
                       boolean noFetch,
                       boolean withCitrusMcp,
                       String sourcePlatform,
                       String commandPrefix,
                       String defaultCitrusVersion,
                       DistributionConfig distribution,
                       Printer printer,
                       InitProgress progress,
                       InitReporter reporter) {
        this(projectName, agentName, targetDir, citrusVersion, noFetch, withCitrusMcp, sourcePlatform,
             commandPrefix, defaultCitrusVersion, distribution, printer, progress, reporter, false, false, false);
    }

    /**
     * Creates a request that does not run the project's tests to record a baseline.
     */
    public InitRequest(
                       String projectName,
                       String agentName,
                       Path targetDir,
                       String citrusVersion,
                       boolean noFetch,
                       boolean withCitrusMcp,
                       String sourcePlatform,
                       String commandPrefix,
                       String defaultCitrusVersion,
                       DistributionConfig distribution,
                       Printer printer,
                       InitProgress progress,
                       InitReporter reporter,
                       boolean dryRun,
                       boolean replaceWrapper) {
        this(projectName, agentName, targetDir, citrusVersion, noFetch, withCitrusMcp, sourcePlatform,
             commandPrefix, defaultCitrusVersion, distribution, printer, progress, reporter, dryRun,
             replaceWrapper, false);
    }

    public InitRequest {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(agentName, "agentName");
        Objects.requireNonNull(targetDir, "targetDir");
        targetDir = targetDir.toAbsolutePath().normalize();
        Objects.requireNonNull(citrusVersion, "citrusVersion");
        Objects.requireNonNull(commandPrefix, "commandPrefix");
        Objects.requireNonNull(defaultCitrusVersion, "defaultCitrusVersion");
        Objects.requireNonNull(distribution, "distribution");
        Objects.requireNonNull(printer, "printer");
        progress = progress == null ? InitProgress.noop() : progress;
        reporter = reporter == null ? InitReporter.noop() : reporter;
    }

    String resolvedCitrusVersion() {
        return "default".equals(citrusVersion) ? defaultCitrusVersion : citrusVersion;
    }
}
