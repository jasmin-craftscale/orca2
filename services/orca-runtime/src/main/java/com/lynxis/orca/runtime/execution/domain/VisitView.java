package com.lynxis.orca.runtime.execution.domain;

import java.time.Instant;

/**
 * A visit as a reader sees it.
 *
 * @param currentActivity the step the engine is parked at, read live. Null on a list
 *                        read (one engine call per row would make a page of results a
 *                        page of round trips) and null once the visit is no longer
 *                        running
 */
public record VisitView(String externalId, String laneExternalId, String status, String plate,
		Instant startedAt, Instant completedAt, String currentActivity) {
}
