package com.lynxis.orca.runtime.execution.api.dto;

/**
 * One thing wrong with a workflow, addressed to the person editing it.
 *
 * @param invariant the compiler invariant that would refuse this — the SAME name the compile
 *     failure carries, so a builder message and a deploy failure are traceably one thing
 * @param subjectId the node, link, branch or response uuid the author should look at; null
 *     when the finding is about the workflow as a whole
 * @param subjectName the authored name of that subject, because a uuid means nothing on a canvas
 * @param message plain language, addressed to an author rather than to a compiler engineer
 */
public record ValidationFinding(String invariant, String subjectId, String subjectName, String message) {
}
