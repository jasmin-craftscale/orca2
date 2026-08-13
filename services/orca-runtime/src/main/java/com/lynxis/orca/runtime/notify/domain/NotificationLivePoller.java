package com.lynxis.orca.runtime.notify.domain;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.system.SystemContext;
import com.lynxis.orca.platform.web.system.SystemIdentity;
import com.lynxis.orca.runtime.notify.api.NotificationLiveConnections;
import com.lynxis.orca.runtime.notify.domain.NotificationTables.Notification;
import com.lynxis.orca.runtime.notify.persistence.NotificationRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort live fan-out for the in-app notification list.
 *
 * <p>The durable {@code notification} row is the source of truth. Every runtime
 * instance polls that table from its own cursor and delivers matching rows to the
 * WebSocket sessions connected to that instance. A missed or duplicated live
 * message is recovered by the normal notification list read on reconnect.
 */
@Slf4j
public class NotificationLivePoller {

	public static final long START_AT_CURRENT_TAIL = -1L;

	private static final String SERVICE = "orca-runtime";

	private final NotificationRepository notifications;
	private final NotificationLiveConnections connections;
	private final String siteExternalId;
	private final int batchSize;
	private final AtomicLong lastSeenNotificationId;

	public NotificationLivePoller(NotificationRepository notifications,
			NotificationLiveConnections connections, String siteExternalId, int batchSize,
			long initialLastSeenNotificationId) {
		if (batchSize <= 0) {
			throw new IllegalStateException("orca.runtime.notifications.live-poll-batch-size must be positive.");
		}
		this.notifications = notifications;
		this.connections = connections;
		this.siteExternalId = siteExternalId;
		this.batchSize = batchSize;
		this.lastSeenNotificationId = new AtomicLong(initialLastSeenNotificationId);
	}

	@Scheduled(fixedDelayString = "${orca.runtime.notifications.live-poll-interval:1s}")
	public void deliverLiveNotifications() {
		SystemContext.runAs(new SystemIdentity(SERVICE, "notification-live-fanout"), () ->
				ScopeContext.runIn(installationScope(), this::deliverUnderScope));
	}

	private void deliverUnderScope() {
		long seen = lastSeenNotificationId.get();
		if (seen == START_AT_CURRENT_TAIL) {
			lastSeenNotificationId.compareAndSet(START_AT_CURRENT_TAIL,
					notifications.maxNotificationId());
			return;
		}
		List<Notification> rows = notifications.createdAfter(seen, batchSize);
		if (rows.isEmpty()) {
			return;
		}
		long maxSeen = seen;
		int delivered = 0;
		for (Notification row : rows) {
			delivered += connections.deliverCreated(row);
			maxSeen = Math.max(maxSeen, row.notificationId());
		}
		lastSeenNotificationId.set(maxSeen);
		if (delivered > 0) {
			log.debug("notification live fan-out delivered {} message(s)", delivered);
		}
	}

	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}
}
