package com.lynxis.orca.runtime.execution.api.dto;

import java.util.List;

/**
 * What a selector may legitimately name at one point in a draft workflow — the data behind
 * the builder's autocomplete (ruling 6, 2026-08-09).
 *
 * <p>The point is not convenience. A selector typed by hand that names something no upstream
 * step produces resolves to empty, the condition compares against empty, and a truck takes
 * the wrong branch with nothing logged. Offering the right thing first is how that class dies
 * at the keyboard rather than at 3am.
 *
 * <p><b>It helps, it does not police.</b> Everything the draft knows is returned; what changes
 * is what the builder should rank highest. A static graph cannot prove every legitimate
 * reference — loops, attach-to-running, a shape still being drawn — and an editor that hides
 * options is one authors route around. The runtime's unresolvable-selector failure remains the
 * backstop for a genuine mistake.
 *
 * @param nodes every step in the draft, each marked {@code runsBefore} the editing point.
 *     Marked rather than filtered: the ones already run are what an author usually wants and
 *     a builder should offer first, but a static graph cannot prove every legitimate
 *     reference, and a tool that hides options gets worked around rather than trusted
 * @param datasetKeys this workflow's dataset vocabulary, each carrying where it was learned
 * @param workflows other deployed workflows, for cross-workflow reads
 * @param helpers the evaluator's helper functions
 */
public record SelectorNamespace(List<NodeRef> nodes, List<DatasetKey> datasetKeys,
        List<WorkflowRef> workflows, List<String> helpers) {

    /**
     * @param runsBefore this step's data already exists at the editing point — a ranking hint
     *     for the builder, never a restriction
     */
    public record NodeRef(String uuid, String name, String type, String selector, boolean runsBefore) {
    }

    /**
     * @param provenance {@code AUTHORED} — some condition in this workflow already reads it,
     *     so it is part of the vocabulary by construction. {@code OBSERVED} — visits of this
     *     workflow have actually written it, which is the stronger evidence and grows as the
     *     runtime runs. A key may be both.
     */
    public record DatasetKey(String key, String selector, String provenance) {
    }

    public record WorkflowRef(String workflowUuid, long workflowId, String name, String selector) {
    }
}
