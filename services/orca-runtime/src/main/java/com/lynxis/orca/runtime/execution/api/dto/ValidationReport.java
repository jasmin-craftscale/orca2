package com.lynxis.orca.runtime.execution.api.dto;

import java.util.List;

/**
 * What the builder shows while somebody is editing: every problem at once, not the first one.
 *
 * <p>{@code compiles} is the question that matters — it answers "would this deploy", and it is
 * answered by asking the real compiler rather than by counting findings, so the two can never
 * disagree about the verdict even if they disagree about the details.
 */
public record ValidationReport(boolean compiles, List<ValidationFinding> findings) {

    public ValidationReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static ValidationReport clean() {
        return new ValidationReport(true, List.of());
    }
}
