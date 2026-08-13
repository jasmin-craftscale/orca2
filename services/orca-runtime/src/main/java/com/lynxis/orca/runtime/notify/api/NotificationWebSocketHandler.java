package com.lynxis.orca.runtime.notify.api;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import lombok.RequiredArgsConstructor;

/**
 * The notification WebSocket endpoint after ticket validation.
 *
 * <p>The socket is local to this runtime instance. Cross-instance delivery is done
 * by the database-backed live poller, which reads the durable notification rows and
 * publishes any matching row to the sessions registered here.
 */
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler {

	private final NotificationLiveConnections connections;

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String operator = (String) session.getAttributes()
				.get(NotificationWebSocketTicketInterceptor.OPERATOR_ATTRIBUTE);
		if (operator == null || operator.isBlank()) {
			session.close(CloseStatus.POLICY_VIOLATION);
			return;
		}
		connections.register(operator, session);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		connections.unregister(session);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
		connections.unregister(session);
		super.handleTransportError(session, exception);
	}
}
