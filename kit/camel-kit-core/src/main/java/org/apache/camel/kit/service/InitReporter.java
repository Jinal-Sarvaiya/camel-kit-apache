package org.apache.camel.kit.service;

import org.apache.camel.kit.generator.ManagedFiles;

/**
 * Receives user-facing initialization events for command-layer rendering.
 */
public interface InitReporter {

    void mavenWrapperCreated(String mavenVersion);

    /** The project already has its own Maven wrapper, which was left untouched. */
    default void mavenWrapperPreserved() {
    }

    /** An existing shared file that Camel-Kit did not write was left untouched. */
    default void conflict(ManagedFiles.Change conflict) {
    }

    void graphBuilt(InitGraphSummary graph);

    void graphSkipped();

    void warning(InitWarning warning);

    static InitReporter noop() {
        return new InitReporter() {
            @Override
            public void mavenWrapperCreated(String mavenVersion) {
            }

            @Override
            public void graphBuilt(InitGraphSummary graph) {
            }

            @Override
            public void graphSkipped() {
            }

            @Override
            public void warning(InitWarning warning) {
            }
        };
    }
}
