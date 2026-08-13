package com.lynxis.orca.runtime.notify;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.notify.api.NotificationController;
import com.lynxis.orca.runtime.notify.api.NotificationLiveConnections;
import com.lynxis.orca.runtime.notify.api.NotificationWebSocketHandler;
import com.lynxis.orca.runtime.notify.api.NotificationWebSocketTicketInterceptor;
import com.lynxis.orca.runtime.notify.domain.NotificationLivePoller;
import com.lynxis.orca.runtime.notify.domain.NotificationService;
import com.lynxis.orca.runtime.notify.persistence.NotificationRepository;
import com.lynxis.orca.runtime.notify.persistence.NotificationTicketRepository;
import com.lynxis.orca.runtime.workitem.api.OperatorIdentity;

/** Wires the notify module. */
@Configuration(proxyBeanMethods = false)
public class NotifyConfiguration {

	@Bean
	public NotificationRepository notificationRepository(ScopeSeam seam) {
		return new NotificationRepository(seam);
	}

	@Bean
	public NotificationTicketRepository notificationTicketRepository(ScopeSeam seam) {
		return new NotificationTicketRepository(seam);
	}

	@Bean
	public NotificationLiveConnections notificationLiveConnections() {
		return new NotificationLiveConnections(new ObjectMapper());
	}

	@Bean
	public NotificationService notificationService(NotificationRepository notifications,
			NotificationTicketRepository tickets, PlatformTransactionManager transactionManager,
			@Value("${orca.installation.site-external-id}") String siteExternalId,
			@Value("${orca.runtime.notifications.ws-ticket-ttl:60s}") Duration ticketTtl) {
		if (ticketTtl.compareTo(Duration.ZERO) <= 0) {
			throw new IllegalStateException("orca.runtime.notifications.ws-ticket-ttl must be positive.");
		}
		return new NotificationService(notifications, tickets,
				new TransactionTemplate(transactionManager), siteExternalId, ticketTtl);
	}

	@Bean
	public NotificationController notificationController(NotificationService notifications,
			OperatorIdentity operatorIdentity,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new NotificationController(notifications, operatorIdentity, siteExternalId);
	}

	@Bean
	public NotificationWebSocketTicketInterceptor notificationWebSocketTicketInterceptor(
			NotificationService notifications,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new NotificationWebSocketTicketInterceptor(notifications, siteExternalId);
	}

	@Bean
	public NotificationWebSocketHandler notificationWebSocketHandler(NotificationLiveConnections liveConnections) {
		return new NotificationWebSocketHandler(liveConnections);
	}

	@Bean
	@ConditionalOnProperty(name = "orca.runtime.notifications.live-poll.enabled",
			havingValue = "true", matchIfMissing = true)
	public NotificationLivePoller notificationLivePoller(NotificationRepository notifications,
			NotificationLiveConnections liveConnections,
			@Value("${orca.installation.site-external-id}") String siteExternalId,
			@Value("${orca.runtime.notifications.live-poll-batch-size:100}") int batchSize) {
		return new NotificationLivePoller(notifications, liveConnections, siteExternalId,
				batchSize, NotificationLivePoller.START_AT_CURRENT_TAIL);
	}
}
