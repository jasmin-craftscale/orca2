package com.lynxis.orca.runtime.notify.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.lynxis.orca.runtime.api.generated.NotificationsApi;
import com.lynxis.orca.runtime.api.generated.model.Notification;
import com.lynxis.orca.runtime.api.generated.model.NotificationEnvelope;
import com.lynxis.orca.runtime.api.generated.model.NotificationListEnvelope;
import com.lynxis.orca.runtime.api.generated.model.NotificationWebSocketTicket;
import com.lynxis.orca.runtime.api.generated.model.NotificationWebSocketTicketEnvelope;
import com.lynxis.orca.runtime.notify.domain.NotificationService;
import com.lynxis.orca.runtime.workitem.api.OperatorIdentity;

/** The durable operator notification list and WebSocket ticket endpoint. */
@RestController
public class NotificationController implements NotificationsApi {

	private final NotificationService notifications;
	private final OperatorIdentity operatorIdentity;
	private final String siteExternalId;

	public NotificationController(NotificationService notifications, OperatorIdentity operatorIdentity,
			String siteExternalId) {
		this.notifications = notifications;
		this.operatorIdentity = operatorIdentity;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<NotificationListEnvelope> listNotifications(Boolean unreadOnly, String type,
			Integer limit) {
		String operator = actingOperator();
		var rows = inScope(() -> notifications.listFor(operator, type, Boolean.TRUE.equals(unreadOnly),
				limit));
		return ResponseEntity.ok(new NotificationListEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(rows.stream().map(NotificationController::toModel).toList()));
	}

	@Override
	public ResponseEntity<NotificationEnvelope> markNotificationRead(String notificationExternalId) {
		String operator = actingOperator();
		var row = inScope(() -> notifications.markRead(notificationExternalId, operator));
		return ResponseEntity.ok(new NotificationEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(toModel(row)));
	}

	@Override
	public ResponseEntity<NotificationWebSocketTicketEnvelope> issueNotificationWebSocketTicket() {
		String operator = actingOperator();
		NotificationService.IssuedTicket ticket = inScope(() -> notifications.issueTicket(operator));
		return ResponseEntity.ok(new NotificationWebSocketTicketEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(new NotificationWebSocketTicket()
						.ticket(ticket.token())
						.expiresAt(offset(ticket.expiresAt()))));
	}

	private <T> T inScope(Supplier<T> action) {
		try {
			return ScopeContext.callIn(installationScope(), action::get);
		}
		catch (NotificationService.NotificationNotFoundException notFound) {
			throw new ApiException(NotificationErrorCode.NOTIFICATION_NOT_FOUND, notFound.getMessage());
		}
	}

	private String actingOperator() {
		return operatorIdentity.operator().orElseThrow(() -> new ApiException(
				NotificationErrorCode.OPERATOR_UNRESOLVED,
				"The request carries no resolvable operator identity, and a notification read "
						+ "without a recipient would leak another operator's inbox."));
	}

	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}

	private static Notification toModel(
			com.lynxis.orca.runtime.notify.domain.NotificationTables.Notification row) {
		return new Notification()
				.externalId(row.externalId())
				.recipientUserExternalId(row.recipientUserExternalId())
				.type(row.type())
				.title(row.title())
				.message(row.message())
				.payloadJson(row.payloadJson())
				.readAt(offset(row.readAt()))
				.createdAt(offset(row.createdAt()));
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
