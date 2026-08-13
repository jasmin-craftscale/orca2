package com.lynxis.orca.runtime.execution.api;

import java.time.Instant;

/**
 * The seam through which {@code integration} admits a partner event into the
 * process owned by {@code execution}.
 *
 * <p>The lane lock, idempotency claim and correlate-or-start decision all belong
 * to {@code execution}. A partner path that reproduced any of them in
 * {@code integration} would be a second admission implementation, and two
 * simultaneous events could start two visits. This port keeps that knowledge on
 * its owning side of the module wall.
 */
public interface PartnerEventAdmissionPort {

	/**
	 * Admits one partner event under the partner event idempotency namespace.
	 *
	 * @throws LaneNotPublishedException when this installation does not publish
	 *         the named lane
	 */
	Outcome admit(Event event) throws LaneNotPublishedException;

	/**
	 * One partner event after the partner-facing module has decoded it.
	 *
	 * <p>There is deliberately no site identifier. The caller establishes the
	 * installation's configured scope; a partner cannot choose it in a request.
	 */
	record Event(String eventUuid, String laneExternalId, String eventType,
			String attributes, Instant occurredAt) {
	}

	/**
	 * The admission answer, without an {@code execution.domain} type crossing the
	 * module wall.
	 *
	 * <p>{@link Status#DUPLICATE} is a successful replay carrying the original
	 * visit. {@link Status#IN_PROGRESS} is non-terminal: another caller still owns
	 * the claim, so it must not be flattened into either success or failure.
	 */
	record Outcome(String eventUuid, Status status, String visitExternalId) {
	}

	/** Every admission answer the caller must preserve. */
	enum Status {
		STARTED,
		CORRELATED,
		DUPLICATE,
		IN_PROGRESS
	}

	/** This installation does not publish the event's lane. */
	class LaneNotPublishedException extends RuntimeException {

		private final String laneExternalId;

		public LaneNotPublishedException(String laneExternalId, Throwable cause) {
			super("Lane '" + laneExternalId + "' is not published by this installation.", cause);
			this.laneExternalId = laneExternalId;
		}

		public String laneExternalId() {
			return laneExternalId;
		}
	}
}
