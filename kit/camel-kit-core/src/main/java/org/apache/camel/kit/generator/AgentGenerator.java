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

    /** Validate the host and plan its catalog once for this initialization or preview. */
    default Prepared prepareCatalog(InitContext ctx, boolean preview) throws Exception {
        if (preview) {
            validate(ctx);
        } else {
            preflight(ctx);
        }
        return new Prepared(ctx.files().planCatalog(ctx), this);
    }

    /** The catalog and the host writer validated for that run. */
    record Prepared(CatalogUpdate catalog, AgentGenerator writer) {
        public void generate() throws Exception {
            catalog.generate(writer);
        }
    }

    void generate(InitContext ctx) throws Exception;
}
