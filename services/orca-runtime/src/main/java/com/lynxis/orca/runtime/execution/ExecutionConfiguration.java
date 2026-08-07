package com.lynxis.orca.runtime.execution;

import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lynxis.orca.runtime.execution.domain.ConnectorCallDelegate;
import com.lynxis.orca.runtime.execution.domain.ConnectorPort;
import com.lynxis.orca.runtime.execution.domain.DeviceCommandDelegate;
import com.lynxis.orca.runtime.execution.domain.DeviceCommandPort;
import com.lynxis.orca.runtime.execution.domain.ProcessEngineGateway;
import com.lynxis.orca.runtime.execution.persistence.FlowableProcessEngineGateway;

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
	 * <p>WP7 supplies the real one and this steps aside.
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
