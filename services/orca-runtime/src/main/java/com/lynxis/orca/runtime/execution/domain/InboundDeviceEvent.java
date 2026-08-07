package com.lynxis.orca.runtime.execution.domain;

import java.time.Instant;

/**
 * One device event as runtime sees it, after edge has normalised it.
 *
 * <p>There is no vendor dialect in this record and there is not meant to be. §C3
 * makes edge the hardware boundary: the camera's {@code ZapPacket}, its
 * {@code LP} elements and its confidence ranking are decoded once, at edge, and
 * what crosses the link is this. A field here that only a plate camera could
 * populate would put the boundary in the wrong service.
 *
 * @param eventUuid        the producer's dedup key, and the idempotency key. A
 *                         camera retrying after a lost acknowledgement sends the
 *                         same one
 * @param laneExternalId   the lane, in core's published vocabulary
 * @param attributes       the normalised half, as a JSON object, stored verbatim.
 *                         Runtime reads {@code plate} out of it and takes no
 *                         position on the rest
 * @param occurredAt       when the device observed it — the device's clock, not
 *                         this service's. Null when the device did not say
 */
public record InboundDeviceEvent(
		String eventUuid,
		String laneExternalId,
		String deviceExternalId,
		String eventType,
		String attributes,
		Instant occurredAt) {
}
