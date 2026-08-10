package com.lynxis.orca.runtime.execution;

import java.util.List;

import org.flowable.engine.RuntimeService;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.idempotency.IdempotencyStore;
import com.lynxis.orca.platform.outbox.OutboxWriter;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.execution.api.DeviceEventController;
import com.lynxis.orca.runtime.execution.api.LaneResetController;
import com.lynxis.orca.runtime.execution.api.ManualStepPort;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.ConnectorCallDelegate;
import com.lynxis.orca.runtime.execution.domain.DeviceCommandDelegate;
import com.lynxis.orca.runtime.execution.domain.DeviceCommandPort;
import com.lynxis.orca.runtime.execution.domain.LaneResetService;
import com.lynxis.orca.runtime.execution.domain.ProcessEngineGateway;
import com.lynxis.orca.runtime.execution.domain.VisitCompletion;
import com.lynxis.orca.runtime.execution.domain.VisitCompletionListener;
import com.lynxis.orca.runtime.execution.domain.WorkItemCreationListener;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.persistence.EdgeDeviceCommandClient;
import com.lynxis.orca.runtime.execution.persistence.FlowableManualSteps;
import com.lynxis.orca.runtime.execution.persistence.FlowableProcessEngineGateway;
import com.lynxis.orca.runtime.integration.api.ConnectorPort;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;

/**
 * Wires the `execution` module.
 *
 * <p><strong>The two delegate bean names are load-bearing and are not an
 * implementation detail.</strong> {@code connectorCallDelegate} and
 * {@code deviceCommandDelegate} are what the visual builder's compiler emits into
 * every process it produces, so a rename here breaks every process already
 * published — including ones running at a customer site. They are listed in
 * {@code docs/BPMN_EXECUTION_PROFILE.md}, which is the contract with that
 * developer.
 */
@Configuration(proxyBeanMethods = false)
public class ExecutionConfiguration {

	@Bean
	public ProcessEngineGateway processEngineGateway(RuntimeService runtimeService) {
		return new FlowableProcessEngineGateway(runtimeService);
	}

	@Bean
	public com.lynxis.orca.runtime.execution.persistence.VisitReadRepository visitReadRepository(
			ScopeSeam seam) {
		return new com.lynxis.orca.runtime.execution.persistence.VisitReadRepository(seam);
	}

	/**
	 * The read side of visits. It takes {@code AdmissionRepository} only for the lane
	 * identifier mapping, which that class already owns in both directions — the seam
	 * permits no joins, so the alternative would be a second copy of that lookup.
	 */
	@Bean
	public com.lynxis.orca.runtime.execution.domain.VisitQueryService visitQueryService(
			com.lynxis.orca.runtime.execution.persistence.VisitReadRepository visits,
			AdmissionRepository lanes, ProcessEngineGateway engine) {
		return new com.lynxis.orca.runtime.execution.domain.VisitQueryService(visits, lanes, engine);
	}

	@Bean
	public AdmissionRepository admissionRepository(ScopeSeam seam) {
		return new AdmissionRepository(seam);
	}

	/**
	 * Admission — the property the whole design turns on.
	 *
	 * <p>The transaction template is built here rather than injected as a bean so
	 * that its settings belong to admission: the engine start, the visit insert and
	 * the idempotency claim share one commit, and a template shared with anything
	 * else would eventually be tuned for that other thing.
	 *
	 * @param holderId this instance's identity in {@code idempotency_record}.
	 *                 Defaults to the hostname, because an operator reading that
	 *                 table needs to know which machine is mid-way through an event
	 */
	@Bean
	public AdmissionService admissionService(AdmissionRepository repository, ProcessEngineGateway engine,
			IdempotencyStore idempotency, PlatformTransactionManager transactionManager,
			@Value("${orca.installation.site-external-id}") String siteExternalId,
			@Value("${orca.runtime.holder-id:${HOSTNAME:runtime-local}}") String holderId,
			@Value("${orca.runtime.gate-visit.connector-name}") String connectorName,
			@Value("${orca.runtime.gate-visit.command-action}") String commandAction,
			@Value("${orca.runtime.gate-visit.command-device}") String commandDeviceExternalId,
			@Value("${orca.runtime.gate-visit.command-deadline-ms}") long commandDeadlineMillis) {

		return new AdmissionService(repository, engine, idempotency,
				new TransactionTemplate(transactionManager), siteExternalId, holderId,
				new AdmissionService.ProcessStartVariables(connectorName, commandAction,
						commandDeviceExternalId, commandDeadlineMillis));
	}

	@Bean
	public DeviceEventController deviceEventController(AdmissionService admission,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new DeviceEventController(admission, siteExternalId);
	}

	// --- out to the hardware, and the end of the visit -----------------------

	/**
	 * The device transport, replacing the deployment-fault fallback below.
	 *
	 * <p>It reaches edge's {@code /internal/commands/v1} with the per-installation
	 * shared credential. No token is minted, because token <em>issuing</em>
	 * would put the identity provider on the gate path.
	 */
	@Bean
	public DeviceCommandPort deviceCommandPort(
			@Value("${orca.runtime.edge-base-url:http://localhost:8083}") String edgeBaseUrl,
			@Value("${orca.internal.shared-credential}") String sharedCredential) {
		return new EdgeDeviceCommandClient(edgeBaseUrl, sharedCredential);
	}

	@Bean
	public VisitCompletion visitCompletion(AdmissionRepository repository, OutboxWriter outbox,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new VisitCompletion(repository, outbox, siteExternalId);
	}

	/**
	 * Registers the visit-completion and work-item-creation engine listeners.
	 *
	 * <p>Through the engine's configuration rather than as {@code @Bean}s of a
	 * Flowable type, so that the listeners are attached once, at startup, to the
	 * one engine — and so that this file remains the only place in the module that
	 * says anything about how the engine is assembled.
	 */
	@Bean
	public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> engineListenerRegistrar(
			VisitCompletion completion, WorkItemIntake workItemIntake,
			AdmissionRepository admissionRepository,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return configuration -> configuration.setEventListeners(List.of(
				new VisitCompletionListener(completion),
				new WorkItemCreationListener(workItemIntake, admissionRepository, siteExternalId)));
	}

	// --- the manual-input wait state ----------------------------------------

	/**
	 * The engine's side of complete-and-advance, behind the module wall's seam.
	 *
	 * <p>The {@code ObjectProvider} breaks a real construction cycle:
	 * {@code engineListenerRegistrar} (needed to <em>build</em> the engine) →
	 * {@code WorkItemIntake} → this port → {@code TaskService} → the engine.
	 * {@code FlowableManualSteps} says why lazy resolution is free here.
	 */
	@Bean
	public ManualStepPort manualStepPort(ObjectProvider<org.flowable.engine.TaskService> taskService) {
		return new FlowableManualSteps(taskService::getObject);
	}

	/**
	 * Lane reset — the one-transaction abort, and the writer that makes
	 * {@code work_item.FAILED} real. Its own transaction template, for the same
	 * reason admission's is its own.
	 */
	@Bean
	public LaneResetService laneResetService(AdmissionRepository repository, ProcessEngineGateway engine,
			WorkItemIntake workItemIntake,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		return new LaneResetService(repository, engine, workItemIntake,
				new TransactionTemplate(transactionManager));
	}

	@Bean
	public LaneResetController laneResetController(LaneResetService laneReset,
			com.lynxis.orca.runtime.workitem.api.OperatorIdentity operatorIdentity,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new LaneResetController(laneReset, operatorIdentity, siteExternalId);
	}

	/**
	 * ⚠️ A controller taking a configuration value needs a {@code @Bean} method like
	 * this one, even though it is annotated {@code @RestController}.
	 *
	 * <p>Component scanning finds the class and then cannot construct it: the site
	 * identifier is a {@code String}, and there is no bean of type {@code String} to
	 * autowire. Declaring it here replaces the scanned definition with one that
	 * supplies the value — and the failure without it is not a compile error but a
	 * context that will not start, which every suite in this service reports at once.
	 */
	@Bean
	public com.lynxis.orca.runtime.execution.api.VisitController visitController(
			com.lynxis.orca.runtime.execution.domain.VisitQueryService visits,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.api.VisitController(visits, siteExternalId);
	}

	// --- the SLA timer ------------------------------------------------------

	/**
	 * Bean name = the compiler's link target for the SLA timer's duration
	 * expression. Do not rename.
	 */
	@Bean
	public com.lynxis.orca.runtime.execution.domain.WorkItemSla workItemSla(WorkItemIntake workItemIntake,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.domain.WorkItemSla(workItemIntake, siteExternalId);
	}

	/** Bean name = the compiler's link target. Do not rename. */
	@Bean
	public com.lynxis.orca.runtime.execution.domain.WorkItemSlaBreachDelegate workItemSlaBreachDelegate(
			WorkItemIntake workItemIntake,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.domain.WorkItemSlaBreachDelegate(workItemIntake,
				siteExternalId);
	}

	/**
	 * Bean name = the compiler's link target. Do not rename.
	 *
	 * <p>The port arrives through an {@link ObjectProvider} rather than as a plain
	 * parameter with a {@code @ConditionalOnMissingBean} fallback beside it, and the
	 * reason is worth stating: {@code @ConditionalOnMissingBean} is only reliable in
	 * an auto-configuration, because inside an ordinary {@code @Configuration} the
	 * condition may be evaluated before the bean it is looking for is registered.
	 * The symptom is two beans of the same type and a startup failure — which is at
	 * least loud, but the shape below cannot produce it at all: there is exactly one
	 * port, chosen here, and the fallback is not a bean.
	 */
	@Bean
	public ConnectorCallDelegate connectorCallDelegate(ObjectProvider<ConnectorPort> connectorPort) {
		return new ConnectorCallDelegate(
				connectorPort.getIfAvailable(ExecutionConfiguration::unconfiguredConnectorPort));
	}

	/** Bean name = the compiler's link target. Do not rename. */
	@Bean
	public DeviceCommandDelegate deviceCommandDelegate(ObjectProvider<DeviceCommandPort> deviceCommandPort) {
		return new DeviceCommandDelegate(
				deviceCommandPort.getIfAvailable(ExecutionConfiguration::unconfiguredDeviceCommandPort));
	}

	/**
	 * The connector port an installation has not configured yet.
	 *
	 * <p>Present so that the service starts and the process runs to a state a human
	 * can see, rather than failing to start with a bean-resolution error that says
	 * nothing about gates. It raises the same failure a genuinely unreachable
	 * customer system would, so the visit reaches "manual handling required" — the
	 * one outcome that is visible to the people at the gate.
	 *
	 * <p>When installation configuration supplies the real connector, this fallback
	 * steps aside.
	 */
	static ConnectorPort unconfiguredConnectorPort() {
		return call -> {
			throw new ConnectorPort.ConnectorUnavailableException(
					"No connector transport is configured in this installation, so connector '"
							+ call.connectorName() + "' cannot be invoked. This is a deployment gap, "
							+ "not a customer system that said no.");
		};
	}

	/**
	 * The device port an installation has not configured yet.
	 *
	 * <p>Unlike the connector, this throws rather than taking a business branch —
	 * deliberately. A missing device transport is a misconfigured installation, and
	 * routing it to a clerk as though it were an exception in a truck's paperwork
	 * would put a deployment fault into a queue where nobody can fix it. It becomes
	 * a dead-letter job, which is loud and belongs to site operations.
	 */
	static DeviceCommandPort unconfiguredDeviceCommandPort() {
		return command -> {
			throw new IllegalStateException(
					"No device-command transport is configured in this installation, so the '"
							+ command.action() + "' command for lane " + command.laneExternalId()
							+ " cannot be issued. This is a deployment fault, not a device fault.");
		};
	}
}
