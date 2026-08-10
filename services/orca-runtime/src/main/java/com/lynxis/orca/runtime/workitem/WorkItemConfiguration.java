package com.lynxis.orca.runtime.workitem;

import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.execution.api.LaneVisitPort;
import com.lynxis.orca.runtime.execution.api.ManualStepPort;
import com.lynxis.orca.runtime.workitem.api.OperatorIdentity;
import com.lynxis.orca.runtime.workitem.api.WorkItemController;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;
import com.lynxis.orca.runtime.workitem.persistence.RoutingReadRepository;
import com.lynxis.orca.runtime.workitem.persistence.WorkItemRepository;

/**
 * Wires the {@code workitem} module — the first classes it has ever had
 * ({@code ImportedSetGuard} recorded its emptiness until now, and fails the build
 * if it is ever governed-over-nothing again).
 */
@Configuration(proxyBeanMethods = false)
public class WorkItemConfiguration {

	@Bean
	public WorkItemRepository workItemRepository(ScopeSeam seam) {
		return new WorkItemRepository(seam);
	}

	@Bean
	public RoutingReadRepository routingReadRepository(ScopeSeam seam) {
		return new RoutingReadRepository(seam);
	}

	/**
	 * The lifecycle. The transaction template is built here rather than shared:
	 * complete-and-advance is this module's one-transaction guarantee, and a
	 * template shared with anything else would eventually be tuned for that other
	 * thing — the same reasoning admission's wiring records.
	 */
	@Bean
	public com.lynxis.orca.runtime.workitem.persistence.PresenceRepository presenceRepository(
			ScopeSeam seam) {
		return new com.lynxis.orca.runtime.workitem.persistence.PresenceRepository(seam);
	}

	@Bean
	public com.lynxis.orca.runtime.workitem.domain.PresenceService presenceService(
			com.lynxis.orca.runtime.workitem.persistence.PresenceRepository repository,
			PlatformTransactionManager transactionManager,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.workitem.domain.PresenceService(repository,
				new TransactionTemplate(transactionManager), siteExternalId);
	}

	@Bean
	public com.lynxis.orca.runtime.workitem.api.PresenceController presenceController(
			com.lynxis.orca.runtime.workitem.domain.PresenceService presence,
			OperatorIdentity operatorIdentity,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.workitem.api.PresenceController(presence, operatorIdentity,
				siteExternalId);
	}

	@Bean
	public WorkItemService workItemService(WorkItemRepository repository, RoutingReadRepository routing,
			com.lynxis.orca.runtime.workitem.domain.PresenceService presence,
			ManualStepPort manualSteps, LaneVisitPort laneVisits,
			PlatformTransactionManager transactionManager,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new WorkItemService(repository, routing, presence, manualSteps, laneVisits,
				new TransactionTemplate(transactionManager), siteExternalId);
	}

	/**
	 * The acting operator: the identity-provider subject of the current request,
	 * resolved to the platform user through core's published operator directory
	 * ({@code topology_operator}). This replaced the first slice's raw
	 * subject-as-actor placeholder in one bean without changing any caller.
	 *
	 * <p>A subject with no linked user answers empty, and the controller refuses
	 * with {@code OPERATOR_UNRESOLVED}: an operator core does not know cannot
	 * hold work, because eligibility, assignment and the audit trail all speak
	 * core's user vocabulary.
	 */
	@Bean
	public OperatorIdentity operatorIdentity(RoutingReadRepository routing) {
		return () -> {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			if (!(authentication instanceof JwtAuthenticationToken jwt)) {
				return Optional.empty();
			}
			String subject = jwt.getToken().getSubject();
			if (subject == null) {
				return Optional.empty();
			}
			return ScopeContext.callIn(
					Scope.of("config_realm", Set.of("INSTALLATION")),
					() -> routing.operatorBySubject(subject));
		};
	}

	@Bean
	public WorkItemController workItemController(WorkItemService workItems,
			OperatorIdentity operatorIdentity,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new WorkItemController(workItems, operatorIdentity, siteExternalId);
	}
}
