package com.lynxis.orca.platform.scope.table;

/**
 * How a table grows.
 *
 * <p>There is no default anywhere for this. {@code RetentionClassRule} fails the
 * build when a traffic-growing table does not name a retention class, and it can
 * identify those tables only when every author declares the growth mode. A default
 * would answer the question on the author's behalf in whichever direction was
 * convenient.
 */
public enum Growth {

	/**
	 * One row per visit, per event, per command, per delivery. Grows for as long as
	 * the gate runs, and therefore needs a retention class.
	 */
	TRAFFIC_GROWING,

	/**
	 * Bounded by configuration rather than by traffic — a lease per coordination
	 * point, a row per lane, a row per registered consumer. Adding a row means
	 * somebody configured something.
	 */
	BOUNDED
}
