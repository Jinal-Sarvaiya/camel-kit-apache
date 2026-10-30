package org.apache.camel.kit.ship.controller;

import org.apache.camel.kit.ship.controller.ShipRun.ExecutionMode;

/** Runs the same handoff, recovery, boundary and publication acceptance contract as Bob. */
class AntigravityNativeWorkerTest extends ShipNativeWorkerTest {

    @Override
    ExecutionMode executionMode() {
        return ExecutionMode.ANTIGRAVITY_NATIVE;
    }
}
