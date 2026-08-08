package com.lynxis.orca.core.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.core.api.generated.AuditApi;
import com.lynxis.orca.core.api.generated.model.AuditEventSummary;
import com.lynxis.orca.core.api.generated.model.AuditEventsEnvelope;
import com.lynxis.orca.core.domain.AuditTables.AuditEvent;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.persistence.AuditEventRepository;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;

/**
 * The audit trail's read surface — newest first, always bounded. A route this
 * phase adds beyond §C1's interface table (recorded in the report): a
 * write-only audit table would be readable only with a database login, which
 * is the operator blindness H3 removed elsewhere.
 */
@RestController
public class AuditEventController implements AuditApi {

	private final AuditEventRepository events;
	private final String siteExternalId;

	public AuditEventController(AuditEventRepository events, String siteExternalId) {
		this.events = events;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<AuditEventsEnvelope> listAuditEvents(Integer limit) {
		int bounded = limit == null ? 100 : Math.min(Math.max(limit, 1), 1000);
		List<AuditEvent> page = ScopeContext.callIn(
				CoreScopes.installation(siteExternalId), () -> events.latest(bounded));
		return ResponseEntity.ok(new AuditEventsEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(page.stream()
						.map(event -> new AuditEventSummary()
								.occurredAt(offset(event.occurredAt()))
								.actor(event.actor())
								.entityType(event.entityType())
								.entityExternalId(event.entityExternalId())
								.action(AuditEventSummary.ActionEnum.fromValue(event.action()))
								.detail(event.detail()))
						.toList()));
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
