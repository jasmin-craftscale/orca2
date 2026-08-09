package com.lynxis.orca.runtime.execution.domain;

/**
 * The names a compiled process and its delegates agree on.
 *
 * <p><strong>These are an API.</strong> The visual builder's compiler emits them
 * and the delegates read them, so renaming one silently breaks every process
 * already published. They are listed in {@code docs/BPMN_EXECUTION_PROFILE.md},
 * which is the document the builder developer works against.
 *
 * <p>Process variables carry <em>correlation keys only</em> — business data
 * lives in platform tables keyed by execution id, which is what keeps the engine's
 * history tables bounded and payloads inside the platform's retention model. The
 * profile refines that by one category, written down rather than assumed: a
 * gateway has to branch on <em>something</em>, so a <strong>branch
 * discriminator</strong> — a short, enumerable status token — is permitted.
 * A response body is not, however small it looks today.
 */
public final class ProcessVariables {

	private ProcessVariables() {
	}

	// --- correlation keys ---------------------------------------------------

	/** The visit's external identifier. Everything else about it is looked up by this. */
	public static final String VISIT_EXTERNAL_ID = "visitExternalId";

	/** The lane, in core's published vocabulary (core.topology_lane). */
	public static final String LANE_EXTERNAL_ID = "laneExternalId";

	/** Which configured connector to invoke — a name, never an endpoint. */
	public static final String CONNECTOR_NAME = "connectorName";

	/** Which device action to issue — RAISE_GATE, LOWER_GATE, … */
	public static final String COMMAND_ACTION = "commandAction";

	/**
	 * Which device the action is issued to, in core's published vocabulary
	 * (core.topology_device).
	 *
	 * <p>⚠️ <strong>Not decoration.</strong> The fielded 1.x device-host
	 * protocol addresses the device <em>in the URL path</em>
	 * ({@code POST /api/{device}/raiseGate}), so a command that
	 * does not name a device cannot be sent. A lane with one barrier still has to
	 * say which barrier, because the barrier's id is the address.
	 */
	public static final String COMMAND_DEVICE_EXTERNAL_ID = "commandDeviceExternalId";

	/** How long the device command may take before its outcome is {@code UNKNOWN}. */
	public static final String COMMAND_DEADLINE_MILLIS = "commandDeadlineMillis";

	// --- branch discriminators ---------------------------------------------

	/** What the customer system said, reduced to a routing token. Never its body. */
	public static final String CONNECTOR_OUTCOME = "connectorOutcome";

	/** EXECUTED · FAILED · UNKNOWN — see {@link DeviceCommandPort}. */
	public static final String DEVICE_COMMAND_OUTCOME = "deviceCommandOutcome";
}
