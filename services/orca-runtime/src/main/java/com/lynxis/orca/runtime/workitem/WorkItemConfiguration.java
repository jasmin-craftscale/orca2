package com.lynxis.orca.runtime.workitem;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.execution.api.ManualStepPort;
import com.lynxis.orca.runtime.workitem.api.OperatorIdentity;
import com.lynxis.orca.runtime.workitem.api.WorkItemController;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;
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

	/**
	 * The lifecycle. The transaction template is built here rather than shared:
	 * complete-and-advance is this module's one-transaction guarantee, and a
	 * template shared with anything else would eventually be tuned for that other
	 * thing — the same reasoning admission's wiring records.
	 */
	@Bean
	public WorkItemService workItemService(WorkItemRepository repository, ManualStepPort manualSteps,
			PlatformTransactionManager transactionManager,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new WorkItemService(repository, manualSteps, new TransactionTemplate(transactionManager),
				siteExternalId);
	}

	/**
	 * The acting operator: the identity-provider subject of the current request.
	 *
	 * <p>⚠️ Slice shape, stated on {@link OperatorIdentity}: once core publishes
	 * its operator directory (the routing work package), this bean resolves the
	 * subject to the user's external id through that view — one bean changes, no
	 * caller does.
	 */
	@Bean
	public OperatorIdentity operatorIdentity() {
		return () -> {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication instanceof JwtAuthenticationToken jwt) {
				return Optional.ofNullable(jwt.getToken().getSubject());
			}
			return Optional.empty();
		};
	}

	@Bean
	public WorkItemController workItemController(WorkItemService workItems,
			OperatorIdentity operatorIdentity,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new WorkItemController(workItems, operatorIdentity, siteExternalId);
	}
}
