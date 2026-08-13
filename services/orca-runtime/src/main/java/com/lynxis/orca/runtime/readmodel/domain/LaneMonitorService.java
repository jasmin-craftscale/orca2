package com.lynxis.orca.runtime.readmodel.domain;

import java.util.List;

import com.lynxis.orca.runtime.readmodel.api.LaneMonitorProjectionPort;
import com.lynxis.orca.runtime.readmodel.persistence.LaneMonitorRepository;

import lombok.RequiredArgsConstructor;

/** Maintains and serves readmodel's lane-monitor projection. */
@RequiredArgsConstructor
public class LaneMonitorService implements LaneMonitorProjectionPort {

	private final LaneMonitorRepository repository;

	public List<LaneMonitorTables.LaneMonitorRow> list(String laneExternalId, Integer limit) {
		return repository.list(laneExternalId, limit);
	}

	public List<LaneMonitorTables.LaneMonitorRow> alerts(String laneExternalId,
			boolean includeOutOfService, Integer limit) {
		return repository.alerts(laneExternalId, includeOutOfService, limit);
	}

	@Override
	public void recordVisitStarted(VisitStarted event) {
		repository.visitStarted(event.laneId(), event.visitExternalId(), event.plate());
	}

	@Override
	public void recordVisitClosed(VisitClosed event) {
		repository.visitClosed(event.laneId(), event.visitExternalId(), event.status());
	}

	@Override
	public void recordDeviceEvent(DeviceEventObserved event) {
		repository.deviceObserved(event.laneId(), event.eventUuid(), event.eventType(),
				event.attributes(), event.occurredAt());
	}

	@Override
	public void recordWorkItemQueued(WorkItemQueued event) {
		repository.workItemQueued(event.laneId(), event.workItemExternalId(), event.queuedAt(),
				event.assignee(), event.slaBreachedAt());
	}

	@Override
	public void recordWorkItemCleared(WorkItemCleared event) {
		repository.workItemCleared(event.laneId(), event.workItemExternalId());
	}

	@Override
	public void recordWorkItemBreach(WorkItemBreach event) {
		repository.workItemBreach(event.laneId(), event.workItemExternalId(), event.slaBreachedAt());
	}
}
