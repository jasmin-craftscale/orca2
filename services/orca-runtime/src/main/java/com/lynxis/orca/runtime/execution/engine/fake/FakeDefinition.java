package com.lynxis.orca.runtime.execution.engine.fake;

import com.lynxis.orca.runtime.execution.engine.DeployableDefinition;
import java.util.List;
import java.util.Objects;

/**
 * The fake's deployable shape: an ordered list of steps, each automatic (runs through) or a
 * wait point (parks until signalled). Enough structure to exercise every seam behaviour —
 * deploy/version, run-to-wait, resume, complete, cancel — with no engine and no database.
 */
public record FakeDefinition(String definitionKey, List<Step> steps) implements DeployableDefinition {

    public FakeDefinition {
        Objects.requireNonNull(definitionKey, "definitionKey");
        steps = List.copyOf(steps);
    }

    public static FakeDefinition of(String definitionKey, Step... steps) {
        return new FakeDefinition(definitionKey, List.of(steps));
    }

    public record Step(String id, Kind kind) {

        public Step {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
        }

        public static Step automatic(String id) {
            return new Step(id, Kind.AUTOMATIC);
        }

        public static Step waitPoint(String id) {
            return new Step(id, Kind.WAIT);
        }

        /** A wait a clerk resolves: it parks like any other, and it also has a task. */
        public static Step clerkWaitPoint(String id) {
            return new Step(id, Kind.CLERK_WAIT);
        }

        public enum Kind {
            AUTOMATIC,
            WAIT,
            CLERK_WAIT;

            boolean parks() {
                return this != AUTOMATIC;
            }
        }
    }
}
