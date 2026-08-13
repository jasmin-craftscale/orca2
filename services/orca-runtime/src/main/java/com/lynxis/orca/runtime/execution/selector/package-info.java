/**
 * The selector port from the Go system, done fixture-first: extract the 333 fixtures from the
 * Go tests, validate the extraction against the Go evaluator first — 333/333 green or the
 * extraction is wrong — then port red-to-green.
 *
 * <p>Fixture home: {@code src/test/resources/fixtures/} as language-neutral JSON
 * (expression, context, expected). The POC's {@code FidelityCheck}
 * ({@code orca-bridge}) is the stronger, bidirectional oracle to run alongside.
 */
package com.lynxis.orca.runtime.execution.selector;
