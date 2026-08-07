package com.lynxis.orca.edge.domain;

/**
 * The way out to a lane's device host — the .NET component that actually moves the
 * barrier.
 *
 * <p>⚠️ <strong>THE OUTBOUND SHAPE IS PROVISIONAL.</strong> §D2 freezes the
 * device-host REST contract and says why — <em>"a field-proven vendor component
 * that loads a driver plugin per device; changing this contract would mean
 * re-certifying every device vendor"</em> — and §C3 names what travels in each
 * direction. What neither states is the request body, the route, or the response
 * document for an outbound command, and <strong>nothing in this repository records
 * them</strong>. The inbound half is specified to the endpoint
 * ({@code /device/{uuid}/data}, {@code /device/{uuid}/io-state}); the outbound half
 * is one sentence.
 *
 * <p>So this is an interface with one provisional implementation, exactly as
 * {@code LprFraming} was before the 1.x listener was extracted. That shape is the
 * honest one: the idempotency, the expiry check and the command log — which are
 * ours and are settled — are testable today, and adopting the real contract is
 * writing one class rather than unpicking assumptions from five.
 *
 * <p><strong>Before this reaches a device host</strong>, someone must obtain the
 * vendor's specification or extract the outbound calls from the 1.x estate, the
 * way {@code docs/lpr-wire-format-from-1x.md} was extracted for the camera. Do not
 * treat a green test suite as evidence that the contract is right: every test here
 * speaks the same provisional dialect as the code.
 */
public interface DeviceHostPort {

	/**
	 * Issues one command and waits for the host's answer, bounded by the deadline.
	 *
	 * <p>§C3: <em>"a command completes when the device host confirms it acted — an
	 * acknowledgement <strong>and</strong> a body that decodes. Anything else is an
	 * unknown outcome."</em> Both halves are the implementation's job, and the
	 * distinction between {@link Outcome#FAILED} and {@link Outcome#UNKNOWN} is the
	 * whole reason this returns a value rather than throwing.
	 */
	Outcome issue(String deviceHostUrl, HostCommand command);

	/**
	 * @param params action parameters as a JSON object, passed through untouched.
	 *               Edge takes no position on what a {@code PRINT} needs — that is
	 *               between the process designer and the device plugin
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
		 * <p><strong>Not a failure, and not a success.</strong> §B10 resolves an
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
