package com.lynxis.orca.runtime.execution.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;
import com.lynxis.orca.runtime.api.generated.VisitsApi;
import com.lynxis.orca.runtime.api.generated.model.Visit;
import com.lynxis.orca.runtime.api.generated.model.LaneResetEnvelope;
import com.lynxis.orca.runtime.api.generated.model.LaneResetResult;
import com.lynxis.orca.runtime.api.generated.model.VisitEnvelope;
import com.lynxis.orca.runtime.api.generated.model.VisitListEnvelope;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.LaneResetService;
import com.lynxis.orca.runtime.execution.domain.VisitQueryService;
import com.lynxis.orca.runtime.execution.domain.VisitView;
import com.lynxis.orca.runtime.workitem.api.OperatorIdentity;
import com.lynxis.orca.runtime.workitem.api.WorkItemErrorCode;

/**
 * Reading visits back.
 *
 * <p>Hand-written against a generated interface: change {@code /api/v1/visits} in
 * {@code orca-runtime.yaml} and this class stops compiling until it matches.
 *
 * <p><strong>Scope comes from configuration, never from the request</strong> — the
 * same rule the device-event route follows, and for the same reason. On an on-site
 * installation exactly one site is primary and it is the one the licence binds to; a
 * site identifier carried on the wire would be a value the caller chooses.
 */
@RestController
public class VisitController implements VisitsApi {

	private final VisitQueryService visits;
	private final LaneResetService laneReset;
	private final OperatorIdentity operatorIdentity;
	private final String siteExternalId;

	public VisitController(VisitQueryService visits, LaneResetService laneReset,
			OperatorIdentity operatorIdentity, String siteExternalId) {
		this.visits = visits;
		this.laneReset = laneReset;
		this.operatorIdentity = operatorIdentity;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<VisitListEnvelope> listVisits(String laneExternalId, String status,
			String plate, OffsetDateTime since, Integer limit) {

		requireKnownStatus(status);

		List<VisitView> found = inScope(() -> visits.search(laneExternalId, status, plate,
				since == null ? null : since.toInstant(), limit));

		return ResponseEntity.ok(new VisitListEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(found.stream().map(VisitController::toModel).toList()));
	}

	@Override
	public ResponseEntity<VisitEnvelope> getVisit(String visitExternalId) {
		VisitView visit = inScope(() -> visits.byExternalId(visitExternalId))
				.orElseThrow(() -> new ApiException(ExecutionErrorCode.VISIT_NOT_FOUND,
						"No visit '" + visitExternalId + "' at this installation."));

		return ResponseEntity.ok(new VisitEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(toModel(visit)));
	}

	@Override
	public ResponseEntity<VisitEnvelope> getLaneVisit(String laneExternalId) {
		Optional<VisitView> visit;
		try {
			visit = inScope(() -> visits.onLane(laneExternalId));
		}
		catch (AdmissionService.LaneNotAtThisInstallationException unknownLane) {
			throw new ApiException(ExecutionErrorCode.LANE_NOT_AT_THIS_INSTALLATION,
					unknownLane.getMessage());
		}

		return ResponseEntity.ok(new VisitEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(visit.map(VisitController::toModel).orElse(null)));
	}

	@Override
	public ResponseEntity<LaneResetEnvelope> abortVisit(String visitExternalId) {
		String actor = operatorIdentity.operator().orElseThrow(() -> new ApiException(
				WorkItemErrorCode.OPERATOR_UNRESOLVED,
				"Aborting a visit needs an actor: it fails the visit and its work items, and "
						+ "the audit trail records who did that."));

		LaneResetService.LaneReset reset;
		try {
			reset = inScope(() -> laneReset.abort(visitExternalId, actor));
		}
		catch (LaneResetService.VisitNotFoundException notFound) {
			throw new ApiException(ExecutionErrorCode.VISIT_NOT_FOUND, notFound.getMessage());
		}
		catch (LaneResetService.VisitNotAbortableException conflict) {
			throw new ApiException(ExecutionErrorCode.VISIT_NOT_ABORTABLE, conflict.getMessage());
		}

		return ResponseEntity.ok(new LaneResetEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(new LaneResetResult()
						.laneExternalId(reset.laneExternalId())
						.visitExternalId(reset.visitExternalId())
						.failedWorkItems(reset.failedWorkItems())));
	}

	/**
	 * ⚠️ <strong>The contract declares this parameter's enum and nothing enforces it.</strong>
	 *
	 * <p>The generator emits a plain {@code String} for an enumerated query parameter,
	 * so a mistyped status reaches the repository, matches no row, and answers
	 * {@code 200} with an empty list. That was the behaviour until this check existed,
	 * and it was found by asking the running service rather than by reading the
	 * contract — which declares the enum and looks, from the document alone, as though
	 * it were validating it.
	 *
	 * <p>The same is not true of {@code limit}: its {@code minimum}/{@code maximum}
	 * become bean-validation annotations and are enforced. Bounds are checked;
	 * membership is not.
	 */
	private static void requireKnownStatus(String status) {
		if (status == null || status.isBlank()) {
			return;
		}
		// Compared against the enum's own values rather than through `fromValue`, which
		// THROWS on an unknown value instead of returning null — so using it as a test
		// would turn a caller's typo into a 500 by way of an uncaught exception.
		boolean known = java.util.Arrays.stream(Visit.StatusEnum.values())
				.anyMatch(candidate -> candidate.getValue().equals(status));
		if (!known) {
			throw new ApiException(ExecutionErrorCode.VISIT_FILTER_UNKNOWN_STATUS,
					"'" + status + "' is not a visit status. This platform has ACTIVE, COMPLETED, "
							+ "MANUAL and FAILED.");
		}
	}

	private <T> T inScope(java.util.concurrent.Callable<T> read) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(siteExternalId)), read);
	}

	private static Visit toModel(VisitView view) {
		Visit visit = new Visit()
				.externalId(view.externalId())
				.laneExternalId(view.laneExternalId())
				.plate(view.plate())
				.currentActivity(view.currentActivity());
		if (view.status() != null) {
			visit.setStatus(Visit.StatusEnum.fromValue(view.status()));
		}
		if (view.startedAt() != null) {
			visit.setStartedAt(view.startedAt().atOffset(java.time.ZoneOffset.UTC));
		}
		if (view.completedAt() != null) {
			visit.setCompletedAt(view.completedAt().atOffset(java.time.ZoneOffset.UTC));
		}
		return visit;
	}
}
