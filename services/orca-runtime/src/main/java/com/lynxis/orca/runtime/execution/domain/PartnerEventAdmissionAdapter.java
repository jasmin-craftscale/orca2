package com.lynxis.orca.runtime.execution.domain;

import com.lynxis.orca.runtime.execution.api.PartnerEventAdmissionPort;

/** Implements the partner-facing admission seam over the one admission service. */
public final class PartnerEventAdmissionAdapter implements PartnerEventAdmissionPort {

	/** Partner ids and camera EventGuids are different producer vocabularies. */
	static final String OPERATION = "partner-event";

	private final AdmissionService admission;

	public PartnerEventAdmissionAdapter(AdmissionService admission) {
		this.admission = admission;
	}

	@Override
	public Outcome admit(Event event) {
		AdmissionService.EventOutcome outcome;
		try {
			outcome = admission.accept(new InboundDeviceEvent(
					event.eventUuid(),
					event.laneExternalId(),
					null,
					event.eventType(),
					event.attributes(),
					event.occurredAt()), OPERATION);
		}
		catch (AdmissionService.LaneNotAtThisInstallationException unmatched) {
			throw new LaneNotPublishedException(unmatched.laneExternalId(), unmatched);
		}

		Status status = switch (outcome.status()) {
			case STARTED -> Status.STARTED;
			case CORRELATED -> Status.CORRELATED;
			case DUPLICATE -> Status.DUPLICATE;
			case IN_PROGRESS -> Status.IN_PROGRESS;
		};
		return new Outcome(outcome.eventUuid(), status, outcome.visitExternalId());
	}
}
