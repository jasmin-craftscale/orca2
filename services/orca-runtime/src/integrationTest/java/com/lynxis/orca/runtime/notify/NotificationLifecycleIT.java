package com.lynxis.orca.runtime.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.api.generated.model.Notification;
import com.lynxis.orca.runtime.notify.api.NotificationController;
import com.lynxis.orca.runtime.notify.api.NotificationLiveConnections;
import com.lynxis.orca.runtime.notify.api.NotificationWebSocketHandler;
import com.lynxis.orca.runtime.notify.api.NotificationWebSocketTicketInterceptor;
import com.lynxis.orca.runtime.notify.domain.NotificationLivePoller;
import com.lynxis.orca.runtime.notify.domain.NotificationService;
import com.lynxis.orca.runtime.notify.domain.NotificationService.PublishNotification;
import com.lynxis.orca.runtime.notify.persistence.NotificationRepository;
import com.lynxis.orca.runtime.workitem.api.OperatorIdentity;

@SpringBootTest(
		classes = { RuntimeApplication.class, NotificationLifecycleIT.OperatorStub.class },
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + NotificationLifecycleIT.SCHEMA,
				"spring.flyway.schemas=" + NotificationLifecycleIT.SCHEMA,
				"spring.flyway.default-schema=" + NotificationLifecycleIT.SCHEMA,
				"orca.required-views=",
				"flowable.async-executor-activate=false",
				"orca.installation.site-external-id=" + NotificationLifecycleIT.SITE,
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
				"orca.runtime.notifications.ws-ticket-ttl=30s",
				"orca.runtime.notifications.live-poll.enabled=false",
		})
@org.springframework.test.annotation.DirtiesContext(
		classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationLifecycleIT {

	static final String SCHEMA = "runtime";
	static final String SITE = "SITE-NOTIFY";
	static final String OPERATOR = "op-notify";

	@Autowired
	private NotificationService notifications;

	@Autowired
	private NotificationController controller;

	@Autowired
	private NotificationWebSocketTicketInterceptor ticketInterceptor;

	@Autowired
	private NotificationWebSocketHandler webSocketHandler;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void pointAtTheSchema(DynamicPropertyRegistry registry) {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		for (String table : List.of("notification_ws_ticket", "notification", "grid_export_job",
				"lane_monitor", "work_item_audit", "work_item", "execution_event", "execution",
				"lane_session")) {
			jdbc.execute("DELETE FROM " + table);
		}
	}

	@Test
	@DisplayName("notifications round-trip and unread state is read_at")
	void notificationsRoundTripAndUnreadStateIsReadAt() {
		var first = publish(OPERATOR, "WORK_ITEM", "Manual work", "Truck needs review");
		var second = publish(OPERATOR, "LANE_ALERT", "Lane alert", "Loop held");
		publish("op-other", "WORK_ITEM", "Other", "Not this operator");

		List<Notification> unread = controller.listNotifications(true, null, 10)
				.getBody().getData();
		assertThat(unread).extracting(Notification::getExternalId)
				.containsExactly(second.externalId(), first.externalId());
		assertThat(unread).allSatisfy(row -> assertThat(row.getReadAt()).isNull());

		Notification read = controller.markNotificationRead(first.externalId()).getBody().getData();
		assertThat(read.getReadAt()).isNotNull();

		List<Notification> afterRead = controller.listNotifications(true, null, 10)
				.getBody().getData();
		assertThat(afterRead).extracting(Notification::getExternalId)
				.containsExactly(second.externalId());

		Notification readAgain = controller.markNotificationRead(first.externalId()).getBody().getData();
		assertThat(readAgain.getReadAt()).isEqualTo(read.getReadAt());
		assertThat(notificationColumns()).doesNotContain("is_active", "is_deleted");
	}

	@Test
	@DisplayName("WebSocket ticket is required and cannot be replayed")
	void websocketTicketIsRequiredAndSingleUse() {
		String ticket = controller.issueNotificationWebSocketTicket().getBody().getData().getTicket();

		Map<String, Object> attributes = new HashMap<>();
		assertThat(openWith(ticket, attributes)).isTrue();
		assertThat(attributes).containsEntry(
				NotificationWebSocketTicketInterceptor.OPERATOR_ATTRIBUTE, OPERATOR);

		assertThat(openWith(ticket, new HashMap<>())).isFalse();
		assertThat(openWith(null, new HashMap<>())).isFalse();
		assertThat(openWith("not-a-ticket", new HashMap<>())).isFalse();
		assertThat(countConsumedTickets()).isEqualTo(1);
	}

	@Test
	@DisplayName("database-backed fan-out reaches a socket on another runtime instance")
	void databaseBackedFanOutReachesAnotherRuntimeInstance() throws Exception {
		NotificationLiveConnections otherInstanceConnections =
				new NotificationLiveConnections(new ObjectMapper());
		NotificationLivePoller otherInstancePoller =
				new NotificationLivePoller(notificationRepository, otherInstanceConnections, SITE, 50, 0);
		RecordingSession socketOnOtherInstance = new RecordingSession();
		socketOnOtherInstance.getAttributes()
				.put(NotificationWebSocketTicketInterceptor.OPERATOR_ATTRIBUTE, OPERATOR);
		new NotificationWebSocketHandler(otherInstanceConnections)
				.afterConnectionEstablished(socketOnOtherInstance);

		var created = publish(OPERATOR, "WORK_ITEM", "Manual work", "Truck needs review");

		otherInstancePoller.deliverLiveNotifications();

		assertThat(socketOnOtherInstance.messages())
				.singleElement()
				.satisfies(message -> assertThat(message)
						.contains("\"type\":\"notification.created\"")
						.contains(created.externalId())
						.contains("\"notificationType\":\"WORK_ITEM\""));
	}

	private com.lynxis.orca.runtime.notify.domain.NotificationTables.Notification publish(
			String recipient, String type, String title, String message) {
		return inScope(() -> notifications.publish(new PublishNotification(recipient, type, title,
				message, "{\"created\":\"test\"}")));
	}

	private boolean openWith(String ticket, Map<String, Object> attributes) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET",
				"/api/v1/notifications/ws");
		if (ticket != null) {
			request.setQueryString("ticket=" + ticket);
		}
		return ticketInterceptor.beforeHandshake(new ServletServerHttpRequest(request),
				new ServletServerHttpResponse(new MockHttpServletResponse()), webSocketHandler, attributes);
	}

	private List<String> notificationColumns() {
		return jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
				+ "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
				String.class, SCHEMA, "notification").stream()
				.map(String::toLowerCase)
				.toList();
	}

	private int countConsumedTickets() {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notification_ws_ticket "
				+ "WHERE consumed_at IS NOT NULL", Integer.class);
		return count == null ? 0 : count;
	}

	private <T> T inScope(Supplier<T> action) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(SITE)), action::get);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class OperatorStub {

		@Bean
		@Primary
		OperatorIdentity notificationTestOperatorIdentity() {
			return () -> Optional.of(OPERATOR);
		}
	}

	private static final class RecordingSession implements WebSocketSession {

		private final String id = UUID.randomUUID().toString();
		private final Map<String, Object> attributes = new HashMap<>();
		private final java.util.List<String> messages = new java.util.ArrayList<>();
		private boolean open = true;

		java.util.List<String> messages() {
			return List.copyOf(messages);
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public URI getUri() {
			return URI.create("/api/v1/notifications/ws");
		}

		@Override
		public HttpHeaders getHandshakeHeaders() {
			return HttpHeaders.EMPTY;
		}

		@Override
		public Map<String, Object> getAttributes() {
			return attributes;
		}

		@Override
		public Principal getPrincipal() {
			return null;
		}

		@Override
		public InetSocketAddress getLocalAddress() {
			return null;
		}

		@Override
		public InetSocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public String getAcceptedProtocol() {
			return null;
		}

		@Override
		public void setTextMessageSizeLimit(int messageSizeLimit) {
		}

		@Override
		public int getTextMessageSizeLimit() {
			return 8192;
		}

		@Override
		public void setBinaryMessageSizeLimit(int messageSizeLimit) {
		}

		@Override
		public int getBinaryMessageSizeLimit() {
			return 8192;
		}

		@Override
		public java.util.List<WebSocketExtension> getExtensions() {
			return List.of();
		}

		@Override
		public void sendMessage(WebSocketMessage<?> message) throws IOException {
			messages.add(String.valueOf(message.getPayload()));
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		@Override
		public void close() throws IOException {
			open = false;
		}

		@Override
		public void close(CloseStatus status) throws IOException {
			open = false;
		}
	}
}
