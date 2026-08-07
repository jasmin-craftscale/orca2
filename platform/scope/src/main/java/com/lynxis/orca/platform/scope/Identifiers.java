package com.lynxis.orca.platform.scope;

/**
 * Table and column names, allow-listed by shape rather than escaped.
 *
 * <p>These reach SQL as text — there is no way to bind an identifier — so the only
 * safe rule is a narrow one: letters, digits and underscore, starting with a
 * letter. Anything else is refused rather than quoted, because quoting is where
 * injection defences go wrong.
 *
 * <p>Extracted so that reads and writes cannot drift apart. Two copies of an
 * allow-list is one copy that eventually gets a special case added to it, and the
 * one that gets the special case is always the newer one.
 */
final class Identifiers {

	private Identifiers() {
	}

	static String require(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A " + what + " name is required");
		}
		if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException(
					"Refusing '" + value + "' as a " + what + " name. Identifiers reach SQL as text "
							+ "and are allow-listed by shape: letters, digits and underscore only.");
		}
		return value;
	}
}
