package com.lynxis.orca.runtime.admission;

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

	/** This event started the visit. Exactly one of two simultaneous events for a lane gets this. */
	record Started(long executionId, String processInstanceId) implements Admission {
	}

	/**
	 * A visit was already running on this lane; this event joins it.
	 *
	 * @param viaBackstop whether the lane lock was bypassed and the filtered unique
	 *                    index is what refused the second visit. In the shipping
	 *                    path this is always {@code false} — the lock serialises
	 *                    first — and a run where it is {@code true} is worth
	 *                    reporting, because it means an inbound path admitted
	 *                    without the lock.
	 */
	record Correlated(long executionId, boolean viaBackstop) implements Admission {
	}
}
