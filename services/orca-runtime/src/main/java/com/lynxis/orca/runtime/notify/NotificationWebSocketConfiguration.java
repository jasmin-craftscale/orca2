package com.lynxis.orca.runtime.notify;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.lynxis.orca.runtime.notify.api.NotificationWebSocketHandler;
import com.lynxis.orca.runtime.notify.api.NotificationWebSocketTicketInterceptor;

/** Registers the ticket-gated notification WebSocket endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class NotificationWebSocketConfiguration implements WebSocketConfigurer {

	private final NotificationWebSocketHandler handler;
	private final NotificationWebSocketTicketInterceptor ticketInterceptor;

	public NotificationWebSocketConfiguration(NotificationWebSocketHandler handler,
			NotificationWebSocketTicketInterceptor ticketInterceptor) {
		this.handler = handler;
		this.ticketInterceptor = ticketInterceptor;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/api/v1/notifications/ws")
				.addInterceptors(ticketInterceptor);
	}
}
