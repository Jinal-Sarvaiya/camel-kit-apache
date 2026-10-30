package org.apache.camel.kit.ship.controller;

import org.apache.camel.kit.ship.controller.ShipRun.ExecutionMode;

/**
 * Validates the shared controller contract for OpenCode native Ship. Generated assets were verified via a local
 * OpenCode workspace probe; live child execution acceptance is tracked in #99.
 */
class OpenCodeNativeWorkerTest extends ShipNativeWorkerTest {

    @Override
    ExecutionMode executionMode() {
        return ExecutionMode.OPENCODE_NATIVE;
    }
}
