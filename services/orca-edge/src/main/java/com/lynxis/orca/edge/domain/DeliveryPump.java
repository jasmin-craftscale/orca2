package com.lynxis.orca.edge.domain;

import java.util.List;

import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;
import com.lynxis.orca.edge.persistence.EventBufferRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Drains each owned lane's buffer to runtime, in order, at least once.
 *
 * <p>Three properties, and each of them is a decision rather than a detail:
 *
 * <ul>
 *   <li><strong>Per lane, in order.</strong> One batch per lane, oldest first, and
 *       a lane that fails does not hold up any other. Facts are ordered per
 *       key, never globally.</li>
 *   <li><strong>At least once, never at most once.</strong> A batch that was sent
 *       and not acknowledged is sent again. Runtime deduplicates on
 *       {@code event_uuid}, so a duplicate delivery has one effect — and that is
 *       the deliberate at-least-once trade: a lost event is
 *       unrecoverable, a repeated one is not.</li>
 *   <li><strong>The batch is not advanced past a failure.</strong> If a lane's
 *       oldest batch cannot be delivered, its newer events wait. Skipping ahead
 *       would drain the lane and silently reorder it.</li>
 * </ul>
 *
 * <p>An event that has failed too many times becomes {@code DEAD} rather than
 * disappearing — visible through {@code /internal/buffer/stats}, because a
 * delivery failure nobody can see is indistinguishable from a capture that never
 * happened.
 */
@Slf4j
public class DeliveryPump {

	private final EventBufferRepository buffer;
	private final LaneOwnership ownership;
	private final EventDeliveryPort delivery;
	private final int batchSize;
	private final int maxAttempts;

	public DeliveryPump(EventBufferRepository buffer, LaneOwnership ownership, EventDeliveryPort delivery,
			int batchSize, int maxAttempts) {
		this.buffer = buffer;
		this.ownership = ownership;
		this.delivery = delivery;
		this.batchSize = batchSize;
		this.maxAttempts = maxAttempts;
	}

	/** One pass over every lane this instance owns. Returns how many events were acknowledged. */
	public int drainOnce() {
		int acked = 0;
		for (String lane : ownership.ownedLanes()) {
			acked += drainLane(lane);
		}
		return acked;
	}

	private int drainLane(String lane) {
		List<BufferedEvent> batch = buffer.undelivered(ownership.siteExternalId(), lane, batchSize);
		if (batch.isEmpty()) {
			return 0;
		}

		List<Long> sequences = batch.stream().map(BufferedEvent::sequenceNo).toList();
		buffer.markDispatched(sequences);

		try {
			delivery.deliver(lane, batch);
			buffer.markAcked(sequences);
			log.debug("lane {}: delivered and acknowledged {} event(s)", lane, batch.size());
			return batch.size();
		}
		catch (RuntimeException notDelivered) {
			// Runtime is down, restarting, or unreachable. Nothing is lost and
			// nothing is skipped: these rows go back to PENDING with their attempt
			// counted, and the next pass starts from the same place.
			buffer.recordFailure(sequences, maxAttempts, notDelivered.getMessage());
			log.warn("lane {}: {} event(s) not delivered ({}). They stay buffered, in order.",
					lane, batch.size(), notDelivered.getMessage());
			return 0;
		}
	}

	/**
	 * The way out to runtime.
	 *
	 * <p>An interface so the pump's ordering and retry behaviour can be proven
	 * against a link that is severed on demand, proving the required outage behaviour
	 * and the one a real HTTP client makes hardest to test.
	 */
	@FunctionalInterface
	public interface EventDeliveryPort {

		/**
		 * Delivers one lane's batch, in the order given.
		 *
		 * @throws RuntimeException when the batch was not accepted. The caller
		 *                          treats every failure identically: nothing is
		 *                          dropped and nothing is skipped
		 */
		void deliver(String laneExternalId, List<BufferedEvent> batch);
	}
}
