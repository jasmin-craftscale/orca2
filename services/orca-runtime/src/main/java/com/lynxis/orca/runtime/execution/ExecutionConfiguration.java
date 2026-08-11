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
import com.lynxis.orca.runtime.execution.engine.flowable.NodeExecutionRecorder;
import com.lynxis.orca.runtime.execution.internal.VisitDataWriter;
import com.lynxis.orca.runtime.execution.persistence.NodeExecutionTraceRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitDatasetRepository;
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
	public com.lynxis.orca.runtime.execution.api.LaneVisitPort laneVisitPort(
			com.lynxis.orca.runtime.execution.persistence.VisitReadRepository visits,
			AdmissionRepository lanes) {
		return new com.lynxis.orca.runtime.execution.domain.LaneVisitLookup(visits, lanes);
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
			NodeExecutionTraceRepository nodeExecutionTrace,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return configuration -> configuration.setEventListeners(List.of(
				new VisitCompletionListener(completion),
				new WorkItemCreationListener(workItemIntake, admissionRepository, siteExternalId),
				new NodeExecutionRecorder(nodeExecutionTrace, siteExternalId)));
	}

	// --- The step trace and the visit dataset --------------------------------
	//
	// What a visit's process actually did, and what it knows: written in the
	// engine's own transaction by the recorder above and by the delegates through
	// the data sink, read back by the selector data provider when a connector
	// body or a condition asks.

	@Bean
	public NodeExecutionTraceRepository nodeExecutionTraceRepository(ScopeSeam seam) {
		return new NodeExecutionTraceRepository(seam);
	}

	@Bean
	public VisitDatasetRepository visitDatasetRepository(ScopeSeam seam) {
		return new VisitDatasetRepository(seam);
	}

	@Bean
	public VisitDataWriter visitDataWriter(VisitDatasetRepository dataset,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new VisitDataWriter(dataset, siteExternalId);
	}

	// --- The facade and the selector's live binding ---------------------------
	//
	// The engine seam's Flowable adapter, the registry the publish path fills,
	// the facade that resumes and inspects visits (never starts them — admission
	// owns the start), and the data provider that binds the ported selector
	// evaluator to this runtime's tables.

	/** The reversibility seam's adapter — the one bean that hands the engine out engine-free. */
	@Bean
	public com.lynxis.orca.runtime.execution.engine.WorkflowEngine workflowEngine(
			org.flowable.engine.ProcessEngine processEngine) {
		return new com.lynxis.orca.runtime.execution.engine.flowable.FlowableWorkflowEngine(processEngine);
	}

	@Bean
	public com.lynxis.orca.runtime.execution.internal.DefinitionRegistry definitionRegistry() {
		return new com.lynxis.orca.runtime.execution.internal.DefinitionRegistry();
	}

	@Bean
	public com.lynxis.orca.runtime.execution.api.ExecutionFacade executionFacade(
			com.lynxis.orca.runtime.execution.engine.WorkflowEngine workflowEngine,
			AdmissionRepository admissionRepository, VisitDataWriter visitDataWriter,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.internal.RuntimeExecutionFacade(
				workflowEngine, admissionRepository, visitDataWriter, siteExternalId);
	}

	@Bean
	public com.lynxis.orca.runtime.execution.persistence.VisitTreeRepository visitTreeRepository(
			ScopeSeam seam) {
		return new com.lynxis.orca.runtime.execution.persistence.VisitTreeRepository(seam);
	}

	/**
	 * The selector's live binding. The catalog is deliberately {@code UNBOUND}:
	 * a configuration question a selector asks before the site-configuration
	 * adapter lands (it comes with the connector phase) fails by the method's
	 * name rather than resolving to an empty string that routes a truck.
	 */
	@Bean
	public com.lynxis.orca.runtime.execution.selector.SelectorDataProvider selectorDataProvider(
			com.lynxis.orca.runtime.execution.persistence.VisitTreeRepository visitTree,
			NodeExecutionTraceRepository nodeExecutionTrace, VisitDatasetRepository visitDataset,
			com.lynxis.orca.runtime.execution.internal.DefinitionRegistry definitionRegistry,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.internal.selector.RuntimeSelectorDataProvider(
				visitTree, nodeExecutionTrace, visitDataset, definitionRegistry,
				com.lynxis.orca.runtime.execution.internal.selector.SiteCatalog.UNBOUND,
				siteExternalId);
	}

	// --- the compiled definitions' delegates -----------------------------------
	//
	// Bean names are load-bearing here exactly as they are for the two gate-process
	// delegates above: the compiler emits ${orcaConnectorDelegate} and friends into
	// every definition it produces. The SPI defaults refuse loudly — a runtime
	// booted without an adapter fails the step by name, never quietly no-ops and
	// never quietly acts.

	/** Trades the engine's instance id for the identifiers selectors resolve against. */
	@Bean
	public com.lynxis.orca.runtime.execution.delegate.spi.VisitIdentity visitIdentity(
			AdmissionRepository admissionRepository,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.internal.RuntimeVisitIdentity(
				admissionRepository, siteExternalId);
	}

	/** Bean name = the compiler's link target. Do not rename. */
	@Bean
	public com.lynxis.orca.runtime.execution.delegate.OrcaConnectorDelegate orcaConnectorDelegate(
			ObjectProvider<com.lynxis.orca.runtime.execution.delegate.spi.ConnectorGateway> gateway,
			RuntimeService runtimeService, VisitDataWriter visitDataWriter,
			com.lynxis.orca.runtime.execution.delegate.spi.VisitIdentity visitIdentity) {
		return new com.lynxis.orca.runtime.execution.delegate.OrcaConnectorDelegate(
				gateway.getIfAvailable(ExecutionConfiguration::unconfiguredConnectorGateway),
				runtimeService, visitDataWriter, visitIdentity);
	}

	/** Bean name = the compiler's link target. Do not rename. */
	@Bean
	public com.lynxis.orca.runtime.execution.delegate.OrcaDeviceEffectDelegate orcaDeviceEffectDelegate(
			ObjectProvider<com.lynxis.orca.runtime.execution.delegate.spi.EdgeClient> edgeClient) {
		return new com.lynxis.orca.runtime.execution.delegate.OrcaDeviceEffectDelegate(
				edgeClient.getIfAvailable(ExecutionConfiguration::unconfiguredEdgeClient));
	}

	/** Bean name = the compiler's link target. Do not rename. */
	@Bean
	public com.lynxis.orca.runtime.execution.delegate.OrcaDisplayDelegate orcaDisplayDelegate(
			ObjectProvider<com.lynxis.orca.runtime.execution.delegate.spi.EdgeClient> edgeClient) {
		return new com.lynxis.orca.runtime.execution.delegate.OrcaDisplayDelegate(
				edgeClient.getIfAvailable(ExecutionConfiguration::unconfiguredEdgeClient));
	}

	/** Bean name = the compiler's link target. Do not rename. */
	@Bean
	public com.lynxis.orca.runtime.execution.delegate.OrcaNotificationDelegate orcaNotificationDelegate(
			ObjectProvider<com.lynxis.orca.runtime.execution.delegate.spi.NotificationSender> sender) {
		return new com.lynxis.orca.runtime.execution.delegate.OrcaNotificationDelegate(
				sender.getIfAvailable(ExecutionConfiguration::unconfiguredNotificationSender));
	}

	/**
	 * The connector gateway an installation has not configured. A connector cannot
	 * no-op — routing needs its response — so this fails the step by name, never
	 * with a fabricated status a compiled branch would route on.
	 */
	static com.lynxis.orca.runtime.execution.delegate.spi.ConnectorGateway unconfiguredConnectorGateway() {
		return request -> {
			throw new IllegalStateException("No ConnectorGateway adapter is configured in this "
					+ "installation, so connector node '" + request.nodeUuid() + "' ('"
					+ request.name() + "') cannot be called. This is a deployment gap, not a "
					+ "customer system that said no.");
		};
	}

	/** The edge an installation has not configured: the effect fails by name. */
	static com.lynxis.orca.runtime.execution.delegate.spi.EdgeClient unconfiguredEdgeClient() {
		return command -> {
			throw new IllegalStateException("No EdgeClient adapter is configured in this "
					+ "installation, so the " + command.kind() + " effect '" + command.name()
					+ "' (" + command.nodeUuid() + ") cannot be performed. This is a deployment "
					+ "fault, not a device fault.");
		};
	}

	/** The notify seam an installation has not configured: the alert fails by name. */
	static com.lynxis.orca.runtime.execution.delegate.spi.NotificationSender unconfiguredNotificationSender() {
		return (idempotencyKey, siteExternalId, nodeUuid, name) -> {
			throw new IllegalStateException("No NotificationSender adapter is configured in this "
					+ "installation, so notification '" + name + "' (" + nodeUuid
					+ ") cannot be sent.");
		};
	}

	// --- the designer's validation surface ------------------------------------

	/** The compiler, as the module's compilation facade — pure, so construction is free. */
	@Bean
	public com.lynxis.orca.runtime.execution.api.CompilationFacade compilationFacade() {
		return new com.lynxis.orca.runtime.execution.compiler.DesignerJsonCompiler();
	}

	@Bean
	public com.lynxis.orca.runtime.execution.internal.selector.NamespaceService namespaceService(
			VisitDatasetRepository visitDataset,
			com.lynxis.orca.runtime.execution.internal.DefinitionRegistry definitionRegistry,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.internal.selector.NamespaceService(
				visitDataset, definitionRegistry, siteExternalId);
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
			LaneResetService laneReset,
			com.lynxis.orca.runtime.workitem.api.OperatorIdentity operatorIdentity,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new com.lynxis.orca.runtime.execution.api.VisitController(visits, laneReset,
				operatorIdentity, siteExternalId);
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
