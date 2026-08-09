package com.lynxis.orca.platform.scope;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the current caller is allowed to see, expressed as named dimensions and
 * the values permitted in each.
 *
 * <p>A dimension is just a name and a set of permitted values — for example, the
 * dimension {@code site_external_id} permitting {@code SITE-HAMBURG}. Every
 * database read goes through a seam that turns whatever this object carries into
 * the query's leading condition, so a caller cannot accidentally read outside
 * what it was granted.
 *
 * <h2>Why this type refuses to know what a dimension means</h2>
 *
 * <p>This class does not know that {@code site_id} identifies a site, or that
 * {@code carrier_id} identifies a haulage company. That is deliberate, and it is
 * not squeamishness about naming things: the platform has to enforce three
 * genuinely different kinds of restriction, and only two of them are about
 * tenancy at all.
 *
 * <ul>
 *   <li>An operator entitled to one site must not read another site's data, even
 *       though both belong to the same customer. That is an authorization rule.</li>
 *   <li>One customer's data must never reach another customer. On a site
 *       installation this is structural — there is only ever one customer in the
 *       database — so there is nothing to enforce.</li>
 *   <li>A driver or a haulage company sees their own bookings across <em>every</em>
 *       terminal they deliver to, and nobody else's. Hauliers legitimately span
 *       customers, so this cannot be expressed as "belongs to tenant X" at all —
 *       it is a question about who the requester <em>is</em>, not which tenant
 *       they are inside.</li>
 * </ul>
 *
 * <p>The third case is the reason for the restraint. Hard-coding a tenant column
 * into this type would quietly rule out the only model that can express it — and
 * how the driver portal authorises access is still an open design question, not
 * settled. Leaving the dimension abstract keeps that decision open for whoever
 * makes it.
 *
 * <p>An empty scope means <em>deny</em>, never <em>allow everything</em>. See
 * {@link #DENY}.
 */
public final class Scope {

	/**
	 * The scope in force when nothing established one.
	 *
	 * <p>A query through the seam under this returns <strong>zero rows, never all
	 * rows</strong>. The failure this prevents is the quiet one: a background job
	 * or a mis-wired filter that never set a scope, silently reading everything,
	 * with no error and no log line.
	 */
	public static final Scope DENY = new Scope(Map.of(), true);

	private final Map<String, Set<String>> dimensions;
	private final boolean deny;

	private Scope(Map<String, Set<String>> dimensions, boolean deny) {
		this.dimensions = dimensions;
		this.deny = deny;
	}

	/** A scope permitting the given values in one dimension. */
	public static Scope of(String dimension, Set<String> values) {
		return builder().permit(dimension, values).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Whether every query under this scope returns nothing. */
	public boolean isDeny() {
		return deny;
	}

	/** Permitted values for one dimension, or an empty set when it is not constrained. */
	public Set<String> permitted(String dimension) {
		return dimensions.getOrDefault(dimension, Set.of());
	}

	public Set<String> dimensions() {
		return dimensions.keySet();
	}

	@Override
	public String toString() {
		return deny ? "Scope[DENY]" : "Scope" + dimensions;
	}

	public static final class Builder {

		private final Map<String, Set<String>> dimensions = new LinkedHashMap<>();

		public Builder permit(String dimension, Set<String> values) {
			if (dimension == null || dimension.isBlank()) {
				throw new IllegalArgumentException("A scope dimension must be named");
			}
			if (values == null || values.isEmpty()) {
				// Permitting nothing in a dimension is not the same as not
				// constraining it, and conflating the two is how a scope becomes
				// accidentally unbounded. Say Scope.DENY if that is what is meant.
				throw new IllegalArgumentException(
						"Dimension '" + dimension + "' was given no values. An empty set is not "
								+ "'unconstrained' — use Scope.DENY if nothing should be visible.");
			}
			dimensions.put(dimension, Set.copyOf(new LinkedHashSet<>(values)));
			return this;
		}

		public Scope build() {
			if (dimensions.isEmpty()) {
				return DENY;
			}
			return new Scope(Map.copyOf(dimensions), false);
		}
	}
}
