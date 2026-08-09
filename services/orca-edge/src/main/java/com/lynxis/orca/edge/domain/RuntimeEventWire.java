package com.lynxis.orca.edge.domain;

import java.time.Instant;
import java.util.List;

import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;

/**
 * What edge sends to runtime's {@code /internal/events/v1}.
 *
 * <p><strong>Hand-written on purpose, and it is a duplication.</strong> The
 * contract is authored in {@code orca-runtime.yaml} and orca-runtime generates its
 * server interface from it; edge cannot generate a client from that file without
 * importing another service's module. Services communicate only through five
 * permitted mechanisms, and a Java import is not one. So the caller's side of
 * the contract is written out here, where a reviewer can see it is a copy, rather than smuggled
 * in as a compile-time dependency that would make the two services one deployable.
 *
 * <p>The cost is real: this can drift from the document. What catches it is the
 * integration test that posts this shape at the real controller — not a comment
 * asking people to remember.
 */
public final class RuntimeEventWire {

	private RuntimeEventWire() {
	}

	/** One lane's events, oldest first: ordering is per lane and never global. */
	public record Batch(List<Event> events) {

		public static Batch of(List<BufferedEvent> buffered) {
			return new Batch(buffered.stream().map(Event::from).toList());
		}
	}

	/**
	 * @param occurredAt <strong>edge's</strong> clock, at the moment the capture was
	 *                   buffered — not runtime's at the moment it was drained. After
	 *                   a link outage those differ by the length of the outage, and
	 *                   the first is the one that says when the truck was at the gate
	 * @param attributes the normalised half, as a JSON object. Edge is the hardware
	 *                   boundary: the vendor's dialect is decoded here and
	 *                   never crosses this link
	 */
	public record Event(
			String eventUuid,
			String laneExternalId,
			String deviceExternalId,
			String eventType,
			Instant occurredAt,
			String attributes) {

		static Event from(BufferedEvent buffered) {
			return new Event(buffered.eventUuid(), buffered.laneExternalId(),
					buffered.deviceExternalId(), buffered.eventType(), buffered.receivedAt(),
					buffered.attributes());
		}
	}
}
