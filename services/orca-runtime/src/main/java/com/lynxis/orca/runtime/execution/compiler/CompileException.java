package com.lynxis.orca.runtime.execution.compiler;

/**
 * A compile error, always carrying the named invariant it violates (P2: the Go executor's
 * silent-skip class — 137 dropped nodes — becomes a loud, named refusal here). There is no
 * warning tier: a workflow either compiles whole or does not compile.
 */
public final class CompileException extends RuntimeException {

    /** The invariant checklist — the original five plus the finds since. */
    public enum Invariant {
        /** C1 — no activity with more than one unconditional outgoing flow. */
        I1_SINGLE_UNCONDITIONAL_FLOW,
        /** C2 — process key {@code proc_<workflowId>}; definition id round-trips ≤ 64 chars. */
        I2_SHORT_KEY,
        /** C3 — every link endpoint exists; a dangling link never compiles to a hop. */
        I3_NO_DANGLING_LINKS,
        /** C4 — a subflow sees the caller's dataset (callActivity + inheritVariables). */
        I4_SUBFLOW_SCOPE,
        /** C5 — element ids are {@code n_<uuid>} NCNames; synthetic ids from counters. */
        I5_NCNAME_IDS,
        /** Cross-definition references use the short key (calledElement — the C2 fix, applied to references). */
        I6_SHORT_KEY_REFERENCES,
        /** D3 — every IO node classifies as wait or effect at compile time. */
        I7_WAIT_EFFECT_SPLIT,
        /** The {@code orca:field} var↔selector sidecar on every conditional flow. */
        I8_FIELD_SIDECAR,
        /** D-2026-08-07-6 — MAP_ITERATOR does not compile; re-author as multi-instance. */
        I9_MAP_ITERATOR_REJECTED,
        /** The payload itself is not a workflow this compiler recognises. */
        MALFORMED_INPUT
    }

    private final Invariant invariant;

    public CompileException(Invariant invariant, String message) {
        super(invariant.name() + ": " + message);
        this.invariant = invariant;
    }

    public Invariant invariant() {
        return invariant;
    }
}
