package com.lynxis.orca.runtime.execution.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;
import com.lynxis.orca.runtime.api.generated.InternalEventsApi;
import com.lynxis.orca.runtime.api.generated.model.DeviceEvent;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventBatch;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventBatchEnvelope;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventBatchResult;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventResult;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.InboundDeviceEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * Where a device event enters the gate brain (§B9's first step).
 *
 * <p>Hand-written, implementing a generated interface — ADR-014. Change
 * {@code /internal/events/v1} in {@code orca-runtime.yaml} and this class stops
 * compiling until it matches; nobody has to notice, the compiler does.
 *
 * <h2>Where this request's scope comes from</h2>
 *
 * <p>The scope seam applies whatever {@code ScopeContext} carries and never invents
 * one, so every entry point establishes it explicitly. This one takes <strong>the
 * installation's own site, from configuration</strong>, and not from the request —
 * deliberately, and for the same reason edge does (§C1: exactly one site is
 * primary, and it is the one the licence binds to). A site identifier carried on
 * the wire would be a value a peer could choose, and the credential on
 * {@code /internal/**} is a per-installation shared secret (ADR-011) that cannot
 * prove <em>which</em> peer is calling — so trusting it to name a site would be
 * reading far more into it than it can carry.
 *
 * <p>A consequence worth stating: an event for a lane at another site does not
 * arrive here scoped wrong, it fails to resolve at all, because
 * {@code core.topology_lane} is read under this same scope.
 */
@Slf4j
@RestController
public class DeviceEventController implements InternalEventsApi {

	private final AdmissionService admission;
	private final String siteExternalId;

	public DeviceEventController(AdmissionService admission, String siteExternalId) {
		this.admission = admission;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<DeviceEventBatchEnvelope> acceptDeviceEvents(DeviceEventBatch batch) {
		List<DeviceEvent> events = batch.getEvents() == null ? List.of() : batch.getEvents();

		DeviceEventBatchResult result = ScopeContext.callIn(installationScope(), () -> admit(events));

		return ResponseEntity.ok(new DeviceEventBatchEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(result));
	}

	/**
	 * Admits the batch in the order it was sent.
	 *
	 * <p>Order matters and is not incidental: §D3 orders facts per key, edge drains
	 * a lane oldest-first, and two events for one lane admitted out of order would
	 * bind the wrong plate to the visit.
	 */
	private DeviceEventBatchResult admit(List<DeviceEvent> events) {
		List<AdmissionService.EventOutcome> outcomes;
		try {
			outcomes = admission.acceptBatch(events.stream().map(DeviceEventController::inbound).toList());
		}
		catch (AdmissionService.LaneNotAtThisInstallationException unmatched) {
			// The WHOLE batch is refused, not this event alone — and nothing in it has
			// been admitted, because acceptBatch resolves every lane before it admits
			// any. Edge acknowledges a batch or none of it, so a partial success would
			// be acknowledged as a whole one and the rest of the batch would be lost.
			log.warn("refusing a batch: {}", unmatched.getMessage());
			throw new ApiException(ExecutionErrorCode.LANE_NOT_AT_THIS_INSTALLATION,
					"Lane '" + unmatched.laneExternalId() + "' is not published for this installation.");
		}

		List<DeviceEventResult> results = new ArrayList<>(outcomes.size());
		int accepted = 0;
		int duplicates = 0;

		for (AdmissionService.EventOutcome outcome : outcomes) {
			if (outcome.isNewlyAdmitted()) {
				accepted++;
			}
			else if (outcome.status() == AdmissionService.EventOutcome.Status.DUPLICATE) {
				duplicates++;
			}
			results.add(new DeviceEventResult()
					.eventUuid(outcome.eventUuid())
					.status(DeviceEventResult.StatusEnum.fromValue(outcome.status().name()))
					.visitExternalId(outcome.visitExternalId()));
		}

		return new DeviceEventBatchResult()
				.accepted(accepted)
				.duplicates(duplicates)
				.results(results);
	}

	private static InboundDeviceEvent inbound(DeviceEvent event) {
		return new InboundDeviceEvent(
				event.getEventUuid(),
				event.getLaneExternalId(),
				event.getDeviceExternalId(),
				event.getEventType(),
				event.getAttributes(),
				occurredAt(event));
	}

	private static Instant occurredAt(DeviceEvent event) {
		return event.getOccurredAt() == null ? null : event.getOccurredAt().toInstant();
	}

	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}
}
