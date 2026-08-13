package com.lynxis.orca.runtime.readmodel;

import com.lynxis.orca.runtime.readmodel.api.LaneMonitorProjectionPort;

/** Test-only projection sink for suites that construct one module in isolation. */
public final class NoopLaneMonitorProjectionPort implements LaneMonitorProjectionPort {

	@Override
	public void recordVisitStarted(VisitStarted event) {
	}

	@Override
	public void recordVisitClosed(VisitClosed event) {
	}

	@Override
	public void recordDeviceEvent(DeviceEventObserved event) {
	}

	@Override
	public void recordWorkItemQueued(WorkItemQueued event) {
	}

	@Override
	public void recordWorkItemCleared(WorkItemCleared event) {
	}

	@Override
	public void recordWorkItemBreach(WorkItemBreach event) {
	}
}
