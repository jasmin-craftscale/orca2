package com.lynxis.orca.platform.scope;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the current caller is allowed to see, expressed as named dimensions and
 * the values permitted in each.
 *
 * <p><strong>Deliberately opaque about what a dimension is.</strong> The seam
 * applies whatever this carries; it does not know that {@code site_id} means a
 * site or that {@code carrier_id} means a haulier. That is not squeamishness
 * about the domain — it is because §B6 describes three genuinely different
 * enforcement problems, one of which (the driver portal) is per-principal rather
 * than per-tenant and cannot be expressed as a tenant column at all. Baking a
 * tenant dimension into this type would decide open register item NEW-1a by
 * accident.
 *
 * <p>An empty scope is <em>deny</em>, not <em>all</em>. See {@link #DENY}.
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
