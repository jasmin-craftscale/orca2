package com.lynxis.orca.core.domain;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import lombok.Getter;

/**
 * A declarative set-replacement request that names the same natural key twice.
 *
 * <p>This is a request-shaped fault, and before this exception existed it was
 * answered by the filtered unique indexes — correctly refused, but as an
 * uncaught {@code DuplicateKeyException} falling through to a 500 on six
 * contracted routes. A review found that incorrect response shape. The services now
 * refuse it here, before any row is touched, and the controllers translate it
 * to {@code VALIDATION_FAILED} with the offending field named. The indexes
 * stay what they were: the backstop, not the answer.
 */
@Getter
public class DuplicateRequestEntryException extends RuntimeException {

	private final String field;
	private final String duplicate;

	public DuplicateRequestEntryException(String field, String duplicate) {
		super("Request repeats " + field + " '" + duplicate + "'");
		this.field = field;
		this.duplicate = duplicate;
	}

	/**
	 * Walks {@code items}, throwing on the first repeated key.
	 *
	 * <p>Keys are compared the way the database's unique indexes will compare
	 * them — case-insensitively, trailing whitespace ignored — because the
	 * server collation is case-insensitive and a guard stricter than Java
	 * string equality but weaker than the index would let {@code PRIMARY} and
	 * {@code primary} through here only to 500 on the backstop (skeptical
	 * review, finding A3).
	 */
	public static <T> void requireDistinct(Iterable<T> items, String field, Function<T, String> key) {
		Set<String> seen = new HashSet<>();
		for (T item : items) {
			String value = key.apply(item);
			if (!seen.add(collationKey(value))) {
				throw new DuplicateRequestEntryException(field, value);
			}
		}
	}

	/** The comparison the CI_AS index actually performs, approximated in Java. */
	public static String collationKey(String value) {
		return value == null ? null : value.stripTrailing().toUpperCase(Locale.ROOT);
	}
}
