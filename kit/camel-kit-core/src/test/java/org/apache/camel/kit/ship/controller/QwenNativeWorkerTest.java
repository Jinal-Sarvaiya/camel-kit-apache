package org.apache.camel.kit.ship.controller;

import org.apache.camel.kit.ship.controller.ShipRun.ExecutionMode;

/** Runs the same handoff, recovery, boundary and publication acceptance contract as Bob. */
class QwenNativeWorkerTest extends ShipNativeWorkerTest {

    @Override
    ExecutionMode executionMode() {
        return ExecutionMode.QWEN_NATIVE;
    }
}
