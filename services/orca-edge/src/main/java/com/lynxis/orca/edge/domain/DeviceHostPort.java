package com.lynxis.orca.edge.domain;

/**
 * The way out to a lane's device host — the .NET component that actually moves the
 * barrier.
 *
 * <p><strong>The outbound shape is translated from the legacy 1.x caller, not invented.</strong>
 * The device-host REST contract is frozen because it belongs to a field-proven vendor
 * component that loads a driver plugin per device; changing it would mean
 * re-certifying every device vendor. The platform contract names what travels in
 * each direction, but not the route, body or response document for an
 * outbound command. {@code docs/device-host-outbound-from-1x.md} does: it was
 * extracted from the ORCA 1.x production caller, the way
 * {@code docs/lpr-wire-format-from-1x.md} was extracted for the camera.
 * {@link RestDeviceHost} implements exactly that and marks what it had to choose.
 *
 * <p><strong>Two things that extraction still does not settle</strong>, and neither
 * may be closed in code:
 *
 * <ol>
 *   <li><strong>The Authorization header</strong> — 1.x mints a Keycloak token per
 *       command; whether the host validates it is unknown and the answer collides
 *       with the rule that the identity provider stays off the barrier path. This
 *       remains an open product-owner question.</li>
 *   <li><strong>{@code PTZ_PRESET} has no route in the extraction.</strong> The action
 *       vocabulary has five actions and the 1.x caller has three calls.
 *       {@link RestDeviceHost} refuses the command rather than inventing a URL for
 *       it.</li>
 * </ol>
 *
 * <p>The interface survives the correction for the reason it was introduced: the
 * idempotency, the expiry check and the command log are ours and were settled
 * already, and adopting a contract meant writing one class rather than unpicking
 * assumptions from five. It earned its keep.
 */
public interface DeviceHostPort {

	/**
	 * Issues one command and waits for the host's answer, bounded by the deadline.
	 *
 * <p>A command completes when the device host confirms it acted: an
 * acknowledgement <strong>and</strong> a body that decodes. Anything else is an
 * unknown outcome. Both halves are the implementation's job, and the
	 * distinction between {@link Outcome#FAILED} and {@link Outcome#UNKNOWN} is the
	 * whole reason this returns a value rather than throwing.
	 */
	Outcome issue(String deviceHostUrl, HostCommand command);

	/**
	 * @param params action parameters as a JSON object.
	 *               <p>⚠️ <strong>No longer passed through untouched, and the change
	 *               is a consequence of the real contract.</strong> The 1.x calls put
	 *               the print format, the IO port and the IO state <em>in the URL</em>
	 *               and send an empty body, so edge has to read them to address the
	 *               host at all. The key names are {@link RestDeviceHost}'s choice —
	 *               marked {@code CHOSEN-HERE} there — because the extraction records
	 *               the wire and not the message 1.x built it from
	 */
	record HostCommand(String commandId, String deviceExternalId, String action, String params,
			long deadlineMillis) {
	}

	/**
	 * @param status         {@code EXECUTED} · {@code FAILED} · {@code UNKNOWN}
	 * @param deviceResponse what the host said, verbatim. Recorded rather than
	 *                       interpreted: after an incident, the platform's reading
	 *                       of the answer is worth less than the answer
	 */
	record Outcome(String status, String deviceResponse, String detail) {

		public static final String EXECUTED = "EXECUTED";
		public static final String FAILED = "FAILED";

		/**
		 * The deadline passed with no answer.
		 *
		 * <p><strong>Not a failure, and not a success.</strong> Resolve an
		 * unknown outcome by <em>looking</em> — verifying the device's actual state —
		 * never by retrying blindly. A caller that coerces this to {@link #FAILED}
		 * produces exactly the hazard the value exists to prevent.
		 */
		public static final String UNKNOWN = "UNKNOWN";

		public static Outcome executed(String deviceResponse) {
			return new Outcome(EXECUTED, deviceResponse, null);
		}

		public static Outcome failed(String deviceResponse, String detail) {
			return new Outcome(FAILED, deviceResponse, detail);
		}

		public static Outcome unknown(String detail) {
			return new Outcome(UNKNOWN, null, detail);
		}
	}
}
