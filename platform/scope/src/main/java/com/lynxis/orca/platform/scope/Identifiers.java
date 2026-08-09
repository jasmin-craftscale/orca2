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

	private static final String BARE = "[A-Za-z][A-Za-z0-9_]*";

	static String require(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A " + what + " name is required");
		}
		if (!value.matches(BARE)) {
			throw new IllegalArgumentException(
					"Refusing '" + value + "' as a " + what + " name. Identifiers reach SQL as text "
							+ "and are allow-listed by shape: letters, digits and underscore only.");
		}
		return value;
	}

	/**
	 * A table name, which may carry <strong>one</strong> schema qualifier.
	 *
	 * <p>A published view such as {@code core.topology_lane} is the only permitted
	 * cross-schema read, while every service read must pass through the scope seam.
	 * Before this method accepted one qualifier, the seam could not name such a
	 * view. The only alternatives were raw JDBC, which {@code ScopeSeamRule}
	 * correctly fails at build time, or mirroring every published view into each
	 * consumer's schema.
	 *
	 * <p>Exactly one dot, and both halves are the same narrow shape. Not a
	 * concession to escaping: {@code core.lane; DROP} still has nowhere to hide,
	 * because nothing here quotes — it refuses.
	 */
	static String requireTable(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A table name is required");
		}
		if (!value.matches(BARE + "(\\." + BARE + ")?")) {
			throw new IllegalArgumentException(
					"Refusing '" + value + "' as a table name. A table is `name` or `schema.name`, "
							+ "each part letters, digits and underscore only — identifiers reach SQL "
							+ "as text and are allow-listed by shape rather than quoted.");
		}
		return value;
	}
}
