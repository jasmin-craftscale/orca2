package com.lynxis.orca.runtime.execution.domain;

import java.util.Optional;

import com.lynxis.orca.runtime.execution.api.LaneVisitPort;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitReadRepository;

/**
 * Which visit is running on a lane — the whole of {@link LaneVisitPort}.
 *
 * <p><strong>Deliberately separate from {@code VisitQueryService}, and the reason is
 * structural rather than tidiness.</strong> That class needs the workflow engine to
 * report a visit's live position. This question does not: it is two repository reads.
 * Implementing the port there would put the engine in the dependency graph of every
 * consumer of this port — and {@code workitem} is one, while the engine's own
 * listener registration depends on {@code workitem}. That is a cycle, and Spring
 * refuses to start on it.
 *
 * <p>So the narrow port gets the narrow implementation, and the cycle cannot form.
 */
public class LaneVisitLookup implements LaneVisitPort {

	private final VisitReadRepository visits;
	private final AdmissionRepository lanes;

	public LaneVisitLookup(VisitReadRepository visits, AdmissionRepository lanes) {
		this.visits = visits;
		this.lanes = lanes;
	}

	@Override
	public Optional<Long> activeVisitOn(String laneExternalId) {
		// The port's own exception, not execution's domain one: the caller has to
		// catch this, and a caller that imports execution.domain to write the catch
		// clause has crossed the wall the port exists to keep.
		long laneId = lanes.laneIdOf(laneExternalId)
				.orElseThrow(() -> new LaneVisitPort.LaneNotPublishedException(laneExternalId));
		return visits.activeOnLane(laneId).map(VisitReadRepository.VisitRow::executionId);
	}
}
