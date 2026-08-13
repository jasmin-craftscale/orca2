package com.lynxis.orca.runtime.workitem.api;

import java.time.Instant;
import java.util.List;

/**
 * Readmodel's sanctioned view of work-item grid data.
 *
 * <p>The queue route belongs to the console grid surface, but the queue ordering
 * and terminal history read belong to the workitem module. This port keeps that
 * ownership explicit and keeps readmodel from importing workitem's domain or
 * persistence packages.
 */
public interface WorkItemGridPort {

	List<GridItem> openQueue(String laneExternalId, String assignee, String teamExternalId,
			int limit);

	List<GridItem> completedWork(String status, String laneExternalId, String assignee,
			Instant completedFrom, Instant completedUntil, int limit);

	record GridItem(
			String externalId,
			String visitExternalId,
			String laneExternalId,
			String processDefinitionKey,
			String nodeReference,
			String screenExternalId,
			String status,
			String assignee,
			Instant queuedAt,
			Instant startedAt,
			Instant completedAt,
			Integer completionDurationSec,
			Instant slaBreachedAt,
			String eventData,
			String correctedEventData) {
	}
}
