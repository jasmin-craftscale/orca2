package com.lynxis.orca.runtime.execution.domain;

/**
 * The way out to the physical world, as the process sees it.
 *
 * <p>§B10: <em>a gate command is confirmed, not assumed.</em> The outcome
 * vocabulary below is the whole reason this is an interface with a documented
 * return rather than a {@code void} call — {@link #UNKNOWN} has to survive every
 * layer between the device host and the process, and a layer that collapsed it
 * into a failure would tell the gate that a barrier did not rise when it did.
 */
public interface DeviceCommandPort {

	/** The host acknowledged and its response decoded. */
	String EXECUTED = "EXECUTED";

	/** The host answered with an error, or its response did not decode. */
	String FAILED = "FAILED";

	/**
	 * The deadline passed with no answer.
	 *
	 * <p><strong>Not a failure, and not a success.</strong> §B10: an unknown outcome
	 * is resolved by <em>looking</em> — verifying the device's actual state — never
	 * by retrying blindly. A caller that coerces this to {@link #FAILED} produces
	 * exactly the hazard the enum exists to prevent.
	 */
	String UNKNOWN = "UNKNOWN";

	/**
	 * Issues one command and waits for its outcome, bounded by the deadline.
	 *
	 * @return one of {@link #EXECUTED}, {@link #FAILED} or {@link #UNKNOWN}
	 */
	String issue(DeviceCommand command);

	/**
	 * @param commandId       the idempotency key. §C3 names it the node-execution
	 *                        id; {@code runtime.node_execution} does not exist until
	 *                        Phase 2, so the engine's own execution id stands in —
	 *                        it is stable for the life of the node execution, which
	 *                        is the property the key needs
	 * @param laneExternalId  which lane, in core's published vocabulary
	 * @param deviceExternalId which device on that lane. <strong>Required</strong>:
	 *                        the device-host contract addresses the device in the
	 *                        URL path (DERIVED-FROM-1X), so a command that names no
	 *                        device cannot be sent at all
	 * @param action          {@code RAISE_GATE}, {@code LOWER_GATE}, … (§C3)
	 * @param deadlineMillis  after which the answer is {@link #UNKNOWN}. §B8: every
	 *                        external call has a deadline and a defined outcome when
	 *                        it is exceeded
	 */
	record DeviceCommand(String commandId, String laneExternalId, String deviceExternalId,
			String action, long deadlineMillis) {
	}
}
