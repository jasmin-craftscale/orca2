package com.lynxis.orca.runtime.execution.api;

import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;
import com.lynxis.orca.runtime.api.generated.LanesApi;
import com.lynxis.orca.runtime.api.generated.model.LaneResetEnvelope;
import com.lynxis.orca.runtime.api.generated.model.LaneResetResult;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.LaneResetService;
import com.lynxis.orca.runtime.workitem.api.OperatorIdentity;
import com.lynxis.orca.runtime.workitem.api.WorkItemErrorCode;

/**
 * Exposes lane reset as a route. One transaction sits behind it — see
 * {@link LaneResetService}. The actor is the authenticated caller; a reset is an
 * operator's deliberate act and lands in the failed items' audit trails as one.
 */
@RestController
public class LaneResetController implements LanesApi {

	private final LaneResetService laneReset;
	private final OperatorIdentity operatorIdentity;
	private final String siteExternalId;

	public LaneResetController(LaneResetService laneReset, OperatorIdentity operatorIdentity,
			String siteExternalId) {
		this.laneReset = laneReset;
		this.operatorIdentity = operatorIdentity;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<LaneResetEnvelope> resetLane(String laneExternalId) {
		String actor = operatorIdentity.operator().orElseThrow(() -> new ApiException(
				WorkItemErrorCode.OPERATOR_UNRESOLVED,
				"A lane reset needs an actor: it fails a visit and its work items, and the "
						+ "audit trail records who did that."));

		LaneResetService.LaneReset reset;
		try {
			reset = ScopeContext.callIn(Scope.of("site_external_id", Set.of(siteExternalId)),
					() -> laneReset.reset(laneExternalId, actor));
		}
		catch (AdmissionService.LaneNotAtThisInstallationException unmatched) {
			throw new ApiException(ExecutionErrorCode.LANE_NOT_AT_THIS_INSTALLATION,
					unmatched.getMessage());
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
}
