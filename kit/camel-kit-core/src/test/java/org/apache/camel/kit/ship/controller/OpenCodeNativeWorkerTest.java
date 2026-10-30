package org.apache.camel.kit.ship.controller;

import org.apache.camel.kit.ship.controller.ShipRun.ExecutionMode;

/** Runs the same handoff, recovery, boundary and publication acceptance contract as other native hosts. */
class OpenCodeNativeWorkerTest extends ShipNativeWorkerTest {

    @Override
    ExecutionMode executionMode() {
        return ExecutionMode.OPENCODE_NATIVE;
    }
}
