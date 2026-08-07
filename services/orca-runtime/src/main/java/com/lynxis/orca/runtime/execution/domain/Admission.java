package com.lynxis.orca.runtime.execution.domain;

/**
 * What one inbound device event did to a lane.
 *
 * <p>Sealed rather than a boolean, because the two outcomes are not "success" and
 * "failure" — they are both correct, and a caller has to be able to tell them
 * apart. {@link Started} means this event began the visit; {@link Correlated}
 * means a visit was already running on that lane and this event belongs to it.
 * Collapsing the two into "ok" is how a second visit gets started by a path that
 * only meant to be tolerant.
 */
public sealed interface Admission {

	/** The identifier of the visit this event belongs to, whichever way it got there. */
	long executionId();

	/** The visit's external identifier — what leaves this service (§B8). */
	String visitExternalId();

	/** This event started the visit. Exactly one of two simultaneous events for a lane gets this. */
	record Started(long executionId, String visitExternalId, String processInstanceId) implements Admission {
	}

	/** A visit was already running on this lane; this event joined it. */
	record Correlated(long executionId, String visitExternalId) implements Admission {
	}
}
