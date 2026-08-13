package com.lynxis.orca.runtime.notify.api;

import java.util.Map;
import java.util.Set;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.notify.domain.NotificationService;

/** Consumes the one-use ticket before the notification WebSocket opens. */
public class NotificationWebSocketTicketInterceptor implements HandshakeInterceptor {

	public static final String OPERATOR_ATTRIBUTE = "operatorExternalId";

	private final NotificationService notifications;
	private final String siteExternalId;

	public NotificationWebSocketTicketInterceptor(NotificationService notifications,
			String siteExternalId) {
		this.notifications = notifications;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Map<String, Object> attributes) {
		String ticket = UriComponentsBuilder.fromUri(request.getURI())
				.build()
				.getQueryParams()
				.getFirst("ticket");
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(siteExternalId)),
				() -> notifications.consumeTicket(ticket)
						.map(claim -> {
							attributes.put(OPERATOR_ATTRIBUTE, claim.recipientUserExternalId());
							return true;
						})
						.orElse(false));
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Exception exception) {
	}
}
