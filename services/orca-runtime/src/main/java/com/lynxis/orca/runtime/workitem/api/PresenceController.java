package com.lynxis.orca.runtime.workitem.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;
import com.lynxis.orca.runtime.api.generated.PresenceApi;
import com.lynxis.orca.runtime.api.generated.model.PresenceActivity;
import com.lynxis.orca.runtime.api.generated.model.PresenceEnvelope;
import com.lynxis.orca.runtime.api.generated.model.PresenceListEnvelope;
import com.lynxis.orca.runtime.api.generated.model.PresenceStatus;
import com.lynxis.orca.runtime.api.generated.model.SetPresenceRequest;
import com.lynxis.orca.runtime.workitem.domain.PresenceService;
import com.lynxis.orca.runtime.workitem.domain.PresenceTables.UserActivity;

/** Operator presence — hand-written against the generated interface (ADR-014). */
@RestController
public class PresenceController implements PresenceApi {

	private final PresenceService presence;
	private final OperatorIdentity operatorIdentity;
	private final String siteExternalId;

	public PresenceController(PresenceService presence, OperatorIdentity operatorIdentity,
			String siteExternalId) {
		this.presence = presence;
		this.operatorIdentity = operatorIdentity;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<PresenceEnvelope> getMyPresence() {
		String operator = actingOperator();
		String status = inScope(() -> presence.presenceOf(operator));
		return ResponseEntity.ok(envelope(new PresenceActivity()
				.userExternalId(operator)
				.status(PresenceStatus.fromValue(status))));
	}

	@Override
	public ResponseEntity<PresenceEnvelope> setMyPresence(SetPresenceRequest request) {
		String operator = actingOperator();
		UserActivity activity = inScope(() ->
				presence.setPresence(operator, request.getStatus().getValue()));
		return ResponseEntity.ok(envelope(toModel(activity)));
	}

	@Override
	public ResponseEntity<PresenceListEnvelope> listAssignableOperators() {
		List<UserActivity> assignable = inScope(presence::assignableOperators);
		return ResponseEntity.ok(listEnvelope(assignable));
	}

	@Override
	public ResponseEntity<PresenceListEnvelope> listOperatorActivity(String userExternalId,
			Integer limit) {
		List<UserActivity> history = inScope(() ->
				presence.historyOf(userExternalId, limit == null ? 50 : Math.max(1, Math.min(limit, 500))));
		return ResponseEntity.ok(listEnvelope(history));
	}

	// ------------------------------------------------------------------------

	private String actingOperator() {
		return operatorIdentity.operator().orElseThrow(() -> new ApiException(
				WorkItemErrorCode.OPERATOR_UNRESOLVED,
				"The request carries no resolvable operator identity; presence belongs to a person."));
	}

	private <T> T inScope(Supplier<T> action) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(siteExternalId)), action::get);
	}

	private static PresenceEnvelope envelope(PresenceActivity activity) {
		return new PresenceEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(activity);
	}

	private static PresenceListEnvelope listEnvelope(List<UserActivity> activities) {
		return new PresenceListEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(activities.stream().map(PresenceController::toModel).toList());
	}

	private static PresenceActivity toModel(UserActivity activity) {
		return new PresenceActivity()
				.userExternalId(activity.userExternalId())
				.status(PresenceStatus.fromValue(activity.status()))
				.startedAt(offset(activity.startedAt()))
				.endedAt(offset(activity.endedAt()));
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
