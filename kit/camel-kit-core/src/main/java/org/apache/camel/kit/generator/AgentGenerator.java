package org.apache.camel.kit.generator;

public interface AgentGenerator {
    default void preflight(InitContext ctx) throws Exception {
        // Most generators do not need agent-specific validation before initialization starts.
    }

    /**
     * Runs the checks of {@link #preflight} without changing anything in the project, so a run can be previewed. A
     * generator whose preflight also invalidates assets on failure must override this with the checks alone.
     */
    default void validate(InitContext ctx) throws Exception {
        preflight(ctx);
    }

    void generate(InitContext ctx) throws Exception;
}
