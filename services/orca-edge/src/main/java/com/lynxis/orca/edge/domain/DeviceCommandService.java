package com.lynxis.orca.edge.domain;

import java.time.Instant;
import java.util.Optional;

import com.lynxis.orca.edge.domain.EdgeTables.CommandLogEntry;
import com.lynxis.orca.edge.persistence.CommandLogRepository;
import com.lynxis.orca.platform.idempotency.IdempotencyOutcome;
import com.lynxis.orca.platform.idempotency.IdempotencyStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Performs one device command, at most once, and never after it has gone stale.
 *
 * <h2>The two rules, and the order they run in</h2>
 *
 * <ol>
 *   <li><strong>Claim first.</strong> {@code commandId} — §C3's node-execution id —
 *       is the idempotency key. A replay returns the recorded outcome rather than
 *       acting again, because a caller that retried did so <em>because it never saw
 *       the first answer</em>, and "duplicate" is the one response it cannot use. A
 *       barrier does not rise twice because a network dropped a response.</li>
 *   <li><strong>Then check the deadline, at the last moment before the physical
 *       act.</strong> An expired command is <em>discarded, never delivered</em>. A
 *       command whose deadline has passed describes a world that has moved on: the
 *       truck may have gone and another may be in the lane. Stale actuation is
 *       dangerous in a way a missed command is not — a barrier that rises for
 *       nobody is a barrier that can come down on somebody.</li>
 * </ol>
 *
 * <p>The order matters. Checking expiry before claiming would let two concurrent
 * deliveries of one expired command both discard it and both write a log row, and
 * the {@code command_id} unique constraint would turn the second into a failure
 * where the answer is known.
 *
 * <h2>Why a discarded command is FAILED and not UNKNOWN</h2>
 *
 * <p>{@code UNKNOWN} means nobody knows whether the device acted. Here everybody
 * knows: nothing was sent. §B10 resolves {@code UNKNOWN} by verifying the device,
 * which would be a pointless physical check for a command that never left this
 * process. The distinction from a host that refused lives in {@code detail} —
 * both are {@code FAILED}, and only one of them reached the hardware.
 */
@Slf4j
public class DeviceCommandService {

	/** The idempotency operation name. One namespace per kind of key (§C2). */
	public static final String OPERATION = "device-command";

	private final CommandLogRepository commandLog;
	private final DeviceHostPort deviceHost;
	private final IdempotencyStore idempotency;
	private final String siteExternalId;
	private final String holderId;

	public DeviceCommandService(CommandLogRepository commandLog, DeviceHostPort deviceHost,
			IdempotencyStore idempotency, String siteExternalId, String holderId) {
		this.commandLog = commandLog;
		this.deviceHost = deviceHost;
		this.idempotency = idempotency;
		this.siteExternalId = siteExternalId;
		this.holderId = holderId;
	}

	/**
	 * @param issuedAt when the caller issued it. The deadline is measured from here
	 *                 rather than from arrival, because a command that spent its
	 *                 whole deadline in a queue is exactly the stale one this
	 *                 refuses — and measuring from arrival would make every command
	 *                 look fresh however long it waited
	 */
	public Result issue(Command command, Instant issuedAt) {
		IdempotencyOutcome claim = idempotency.begin(command.commandId(), OPERATION, holderId);

		switch (claim) {
			case IdempotencyOutcome.Completed completed -> {
				// The recorded outcome, byte for byte. Never a bare "duplicate".
				return replayOf(command.commandId(), completed.outcome());
			}
			case IdempotencyOutcome.InProgress inProgress -> {
				log.debug("command {} is already being performed by {}", command.commandId(),
						inProgress.holderId());
				return new Result(command.commandId(), CommandLogEntry.IN_PROGRESS, null,
						"another delivery of this command is in flight", null);
			}
			case IdempotencyOutcome.Fresh ignored -> {
				// Ours to perform.
			}
		}

		try {
			return perform(command, issuedAt);
		}
		catch (RuntimeException beforeAnythingPhysical) {
			// Nothing reached the outside world — a lane that would not resolve, a
			// database that would not answer. Releasing is the right tool ONLY here:
			// once an actuating call has been made, the physical outcome is unknown and
			// unknown is resolved by looking, not by making the command claimable again.
			idempotency.release(command.commandId(), OPERATION, holderId);
			throw beforeAnythingPhysical;
		}
	}

	private Result perform(Command command, Instant issuedAt) {
		// The last moment before the physical act. Not the caller's check repeated:
		// the caller's deadline was true when it was sent, and the queue, the retry
		// and the link outage all happened after that.
		long elapsed = java.time.Duration.between(issuedAt, Instant.now()).toMillis();
		if (elapsed >= command.deadlineMillis()) {
			log.warn("DISCARDING command {} ({}) on lane {}: {} ms elapsed of a {} ms deadline. "
							+ "Nothing was sent to the device host.",
					command.commandId(), command.action(), command.laneExternalId(), elapsed,
					command.deadlineMillis());
			return recordAndAnswer(command, DeviceHostPort.Outcome.failed(null,
					"discarded as expired: " + elapsed + " ms elapsed of a "
							+ command.deadlineMillis() + " ms deadline. Nothing was sent."));
		}

		Optional<String> deviceHostUrl = commandLog.deviceHostUrlOf(command.laneExternalId());
		if (deviceHostUrl.isEmpty()) {
			throw new LaneHasNoDeviceHostException(command.laneExternalId());
		}

		// What is left of the deadline, not the whole of it. A command with 200 ms
		// left must not be given a fresh 5-second wait by the transport.
		long remaining = command.deadlineMillis() - elapsed;
		DeviceHostPort.Outcome outcome = deviceHost.issue(deviceHostUrl.get(),
				new DeviceHostPort.HostCommand(command.commandId(), command.deviceExternalId(),
						command.action(), command.params(), remaining));

		return recordAndAnswer(command, outcome);
	}

	private Result recordAndAnswer(Command command, DeviceHostPort.Outcome outcome) {
		commandLog.record(new CommandLogEntry(0, command.commandId(), siteExternalId,
				command.laneExternalId(), command.deviceExternalId(), command.action(),
				command.params(), command.deadlineMillis(), outcome.status(),
				outcome.deviceResponse(), outcome.detail(), null, Instant.now()));

		// A recorded failure is still a recorded outcome, and replaying must return
		// the same failure rather than re-running the command. `fail` is what makes
		// that true; `complete` would too, but the store's own vocabulary is worth
		// keeping honest for whoever reads idempotency_record after an incident.
		String recorded = outcome.status() + (outcome.detail() == null ? "" : "|" + outcome.detail());
		if (DeviceHostPort.Outcome.EXECUTED.equals(outcome.status())) {
			idempotency.complete(command.commandId(), OPERATION, recorded);
		}
		else {
			idempotency.fail(command.commandId(), OPERATION, recorded);
		}

		return new Result(command.commandId(), outcome.status(), outcome.deviceResponse(),
				outcome.detail(), Instant.now());
	}

	/** The recorded outcome of a command this site was asked to perform before. */
	public Optional<Result> recorded(String commandId) {
		return commandLog.byCommandId(commandId).map(entry -> new Result(entry.commandId(), entry.status(),
				entry.deviceResponse(), entry.detail(), entry.ackedAt()));
	}

	/**
	 * Reconstructs a replay's answer from the log, falling back to the store's
	 * recorded string.
	 *
	 * <p>The log is preferred because it carries the device's own words. The
	 * fallback exists because the store's record and the log row are written in the
	 * same transaction — so if one is missing, something is wrong that a replay
	 * should surface rather than paper over.
	 */
	private Result replayOf(String commandId, String recordedOutcome) {
		return recorded(commandId).orElseGet(() -> {
			int separator = recordedOutcome.indexOf('|');
			String status = separator < 0 ? recordedOutcome : recordedOutcome.substring(0, separator);
			String detail = separator < 0 ? null : recordedOutcome.substring(separator + 1);
			return new Result(commandId, status, null, detail, null);
		});
	}

	/**
	 * @param deadlineMillis how long the command remains worth performing, from
	 *                       {@code issuedAt}
	 */
	public record Command(String commandId, String laneExternalId, String deviceExternalId,
			String action, String params, long deadlineMillis) {
	}

	public record Result(String commandId, String status, String deviceResponse, String detail,
			Instant ackedAt) {
	}

	/** The lane exists but has no device host configured, or is not this site's. */
	public static class LaneHasNoDeviceHostException extends RuntimeException {

		private final String laneExternalId;

		public LaneHasNoDeviceHostException(String laneExternalId) {
			super("Lane '" + laneExternalId + "' has no device host published for this installation's "
					+ "site, so there is nothing to command. That is a configuration gap, not a device "
					+ "fault.");
			this.laneExternalId = laneExternalId;
		}

		public String laneExternalId() {
			return laneExternalId;
		}
	}
}
