package com.lynxis.orca.runtime.execution.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One piece of open clerk work as the engine holds it — the engine-side half of a work item.
 *
 * <p>{@code nodeId} is the compiled element id, not the ORCA node uuid: the {@code n_} prefix
 * is a compiler invariant (I5) and the seam deliberately does not know it, exactly as
 * {@code signal} does not (the facade owns that translation). {@code assignee} is empty while
 * the item is queued — unassigned IS queued; there is no second
 * status field on the engine side to disagree with it.
 */
public record ClerkTask(String taskId, EngineInstanceRef instance, String nodeId,
        String assignee, Instant createdAt) {

    public ClerkTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(nodeId, "nodeId");
    }

    /** The clerk holding this item, or empty while it is queued. */
    public Optional<String> assignedTo() {
        return Optional.ofNullable(assignee).filter(a -> !a.isBlank());
    }
}
