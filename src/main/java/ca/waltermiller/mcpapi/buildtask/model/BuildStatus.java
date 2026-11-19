package ca.waltermiller.mcpapi.buildtask.model;

/**
 * Represents the status of a build throughout its lifecycle.
 */
public enum BuildStatus {
    CREATED,      // Build has been created but no tasks executed
    IN_PROGRESS,  // Build execution has started
    COMPLETED,    // All tasks have been executed successfully
    FAILED,       // Build execution failed
    REVERTED,     // An NBT placement was undone from its pre-placement snapshot
    SUPERSEDED;   // An NBT placement was replaced in place by a revision (a child build)

    /** True when the build's blocks are no longer in the world: undone or replaced by a revision. */
    public boolean removedFromWorld() {
        return this == REVERTED || this == SUPERSEDED;
    }
}
