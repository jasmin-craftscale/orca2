package com.lynxis.orca.platform.scope;

/**
 * A write the current {@link Scope} does not permit.
 *
 * <p><strong>Why a write throws where a read returns nothing.</strong> The seam is
 * deny-by-default in both directions, but the two failures are not alike. A read
 * that returns nothing hands the caller an empty list, and an empty list is a
 * thing a caller can see and act on. A write that changes nothing is
 * indistinguishable, from the caller's side, from a write that succeeded — same
 * absence of exception, same next line of code. The row is simply not there
 * later, and nothing in any log says why.
 *
 * <p>So an out-of-scope write is loud. It is either an authorization failure worth
 * an error, or a background task that never established a scope — and the second
 * one is the case this whole primitive exists to make impossible to do quietly.
 */
public class ScopeViolationException extends SecurityException {

	public ScopeViolationException(String message) {
		super(message);
	}

	static ScopeViolationException noScopeFor(String table, String dimension) {
		return new ScopeViolationException(
				"Refusing to write to '" + table + "': the current scope permits nothing in dimension '"
						+ dimension + "'. Either nothing established a scope for this unit of work — "
						+ "background work must set one deliberately, there is no implicit scope for "
						+ "system tasks — or the caller is not entitled to write here. A write with no "
						+ "scope is refused rather than silently applied to nothing.");
	}

	static ScopeViolationException valueNotPermitted(String table, String dimension, Object value) {
		return new ScopeViolationException(
				"Refusing to write to '" + table + "': scope dimension '" + dimension + "' does not "
						+ "permit the value '" + value + "'. A row may only be written into a scope the "
						+ "caller already holds — otherwise a caller entitled to one site could create "
						+ "rows in another, which no later read would ever reveal to them.");
	}
}
