package org.apache.camel.kit.ship.controller;

import org.apache.camel.kit.ship.controller.ShipRun.ExecutionMode;

/**
 * Validates the shared controller contract (handoff, recovery, boundary, publication) for {@code QWEN_NATIVE}.
 * Generated assets were verified via a local Qwen Code 0.25.3 parser/command-loader probe; live child execution
 * acceptance is tracked in #98.
 */
class QwenNativeWorkerTest extends ShipNativeWorkerTest {

    @Override
    ExecutionMode executionMode() {
        return ExecutionMode.QWEN_NATIVE;
    }
}
