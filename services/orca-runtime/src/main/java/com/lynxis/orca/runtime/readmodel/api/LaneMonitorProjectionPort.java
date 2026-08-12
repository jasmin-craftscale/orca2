package com.lynxis.orca.runtime.readmodel.api;

import java.time.Instant;

/**
 * The only write seam into the lane-monitor projection.
 *
 * <p>Other runtime modules publish the fact they just committed, and readmodel
 * updates its own row inside the caller's transaction. They never read or write
 * readmodel persistence directly, and readmodel never reaches into their tables.
 */
public interface LaneMonitorProjectionPort {

	void recordVisitStarted(VisitStarted event);

	void recordVisitClosed(VisitClosed event);

	void recordDeviceEvent(DeviceEventObserved event);

	void recordWorkItemQueued(WorkItemQueued event);

	void recordWorkItemCleared(WorkItemCleared event);

	void recordWorkItemBreach(WorkItemBreach event);

	record VisitStarted(
			String siteExternalId,
			long laneId,
			String laneExternalId,
			String visitExternalId,
			String plate) {
	}

	record VisitClosed(
			String siteExternalId,
			long laneId,
			String laneExternalId,
			String visitExternalId,
			String status) {
	}

	record DeviceEventObserved(
			String siteExternalId,
			long laneId,
			String laneExternalId,
			String eventUuid,
			String eventType,
			String attributes,
			Instant occurredAt) {
	}

	record WorkItemQueued(
			String siteExternalId,
			long laneId,
			String laneExternalId,
			String workItemExternalId,
			Instant queuedAt,
			String assignee,
			Instant slaBreachedAt) {
	}

	record WorkItemCleared(
			String siteExternalId,
			long laneId,
			String laneExternalId,
			String workItemExternalId) {
	}

	record WorkItemBreach(
			String siteExternalId,
			long laneId,
			String laneExternalId,
			String workItemExternalId,
			Instant slaBreachedAt) {
	}
}
