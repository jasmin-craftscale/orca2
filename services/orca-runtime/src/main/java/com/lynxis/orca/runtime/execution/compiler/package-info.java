/**
 * The ORCA designer-JSON → BPMN compiler. <b>Pure by decree</b>:
 * no Spring, no datasource, no clock, no randomness — ArchUnit rule 7 enforces it, because
 * determinism is T2's make-or-break byte-identity gate.
 *
 * <p>Seeded from the POC's {@code orca-bridge} ({@code DbReader}/{@code Graph}/{@code
 * BpmnEmitter}); the invariants come first (one class per invariant under
 * {@code invariant/}), goldens second. The original five refusals are the invariant checklist.
 */
package com.lynxis.orca.runtime.execution.compiler;
