package org.apache.camel.kit.ship.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.apache.camel.kit.config.DistributionConfig;
import org.apache.camel.kit.ship.context.ShipContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify the actual issued DISCOVERY task prompt contains contract rules.
 */
@DisplayName("Ship DISCOVERY Prompt")
class ShipDiscoveryPromptTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Issued DISCOVERY task must contain contract rules in prompt")
    void issuedDiscoveryPromptContainsContractRules() throws Exception {
        // Set up native fixture
        Path state = tempDir.resolve("state");
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneId.of("UTC"));

        ShipController controller = new ShipController(state, clock, Map.of());
        DistributionConfig distribution = DistributionConfig.loadBundled();
        ShipCoordinator coordinator = new ShipCoordinator(
                state, controller,
                new ShipNativeWorker(
                        controller, Duration.ofMinutes(5), Map.of(), clock,
                        ShipRun.ExecutionMode.BOB2_NATIVE),
                target -> {
                    throw new IOException("Fixture stops before catalog validation");
                },
                new ShipMainValidator(), distribution, Map.of(), false, clock);

        // Start a DISCOVERY run
        ShipRun run = controller.start(project, ShipRun.Oversight.NEVER,
                List.of(new ShipContext.TextInput("Generate an orders route")),
                ShipRun.ExecutionMode.BOB2_NATIVE);
        run = coordinator.run(run.id());

        // Get the actual issued DISCOVERY task
        ShipNativeWorker.Task task = controller.pendingTask(run);
        String issuedPrompt = task.prompt();

        // Verify contract rules are in the issued prompt
        assertTrue(issuedPrompt.contains("skip the question for that boundary"),
                "Issued DISCOVERY prompt must contain skip instruction");

        // Must contain all four contract statuses in the issued prompt
        assertTrue(issuedPrompt.contains("(1) existing"),
                "Issued prompt must contain status (1) existing");
        assertTrue(issuedPrompt.contains("(2) pending"),
                "Issued prompt must contain status (2) pending");
        assertTrue(issuedPrompt.contains("(3) designed"),
                "Issued prompt must contain status (3) designed");
        assertTrue(issuedPrompt.contains("(4) not-applicable"),
                "Issued prompt must contain status (4) not-applicable");

        // Must contain recording instructions in the issued prompt
        assertTrue(issuedPrompt.contains("grouped report structure"),
                "Issued prompt must contain grouped report structure instruction");
        assertTrue(issuedPrompt.contains("analyzed material"),
                "Issued prompt must reference analyzed material in report structure");
    }
}
