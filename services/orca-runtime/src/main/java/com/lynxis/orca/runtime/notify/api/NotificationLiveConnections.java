package com.lynxis.orca.runtime.notify.api;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxis.orca.runtime.notify.domain.NotificationTables.Notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Local WebSocket sessions held by this runtime instance only. */
@Slf4j
@RequiredArgsConstructor
public class NotificationLiveConnections {

	private final ObjectMapper objectMapper;
	private final Map<String, Set<WebSocketSession>> byOperator = new ConcurrentHashMap<>();
	private final Map<String, String> sessionOperators = new ConcurrentHashMap<>();

	public void register(String operatorExternalId, WebSocketSession session) {
		sessionOperators.put(session.getId(), operatorExternalId);
		byOperator.computeIfAbsent(operatorExternalId, ignored -> ConcurrentHashMap.newKeySet())
				.add(session);
	}

	public void unregister(WebSocketSession session) {
		String operator = sessionOperators.remove(session.getId());
		if (operator == null) {
			return;
		}
		Set<WebSocketSession> sessions = byOperator.get(operator);
		if (sessions == null) {
			return;
		}
		sessions.remove(session);
		if (sessions.isEmpty()) {
			byOperator.remove(operator, sessions);
		}
	}

	public int deliverCreated(Notification notification) {
		Set<WebSocketSession> sessions = byOperator.get(notification.recipientUserExternalId());
		if (sessions == null || sessions.isEmpty()) {
			return 0;
		}
		TextMessage message = new TextMessage(payloadOf(notification));
		int delivered = 0;
		for (WebSocketSession session : sessions) {
			if (!session.isOpen()) {
				unregister(session);
				continue;
			}
			if (send(session, message)) {
				delivered++;
			}
		}
		return delivered;
	}

	private boolean send(WebSocketSession session, TextMessage message) {
		try {
			synchronized (session) {
				session.sendMessage(message);
			}
			return true;
		}
		catch (IOException ex) {
			log.debug("notification WebSocket delivery failed for session {}", session.getId(), ex);
			unregister(session);
			return false;
		}
	}

	private String payloadOf(Notification notification) {
		try {
			return objectMapper.writeValueAsString(new CreatedNotificationMessage(
					"notification.created",
					notification.externalId(),
					notification.type(),
					notification.title(),
					notification.message(),
					notification.payloadJson(),
					notification.createdAt().toString()));
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Notification live payload could not be encoded.", ex);
		}
	}

	private record CreatedNotificationMessage(
			String type,
			String notificationExternalId,
			String notificationType,
			String title,
			String message,
			String payloadJson,
			String createdAt) {
	}
}
