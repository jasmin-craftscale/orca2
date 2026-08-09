package com.lynxis.orca.runtime.execution.domain;

import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The compiled process's link target for "command a device".
 *
 * <p>Same rule as {@link ConnectorCallDelegate}: {@code ${deviceCommandDelegate}}
 * is a stable, generic bean name the compiler binds to, parameterised by variables
 * rather than specialised per action.
 *
 * <p><strong>The idempotency key is the engine's execution id.</strong> A command
 * must use the node-execution id, and that is what this is: it is stable for the
 * life of this node execution and changes when the step is genuinely re-entered.
 * A key derived from the visit alone would make a second, legitimate barrier
 * command look like a replay of the first.
 */
@Slf4j
@RequiredArgsConstructor
public class DeviceCommandDelegate implements JavaDelegate {

	/** The BPMN error code the failure branch catches. Part of the profile. */
	public static final String DEVICE_COMMAND_FAILED = "device.command.failed";

	/**
	 * Raised when the physical outcome is not known.
	 *
	 * <p>Deliberately a <em>different</em> code from {@link #DEVICE_COMMAND_FAILED}.
	 * An unknown outcome is resolved by verifying the device's actual
	 * state, never by retrying and never by assuming it failed — so a process must
	 * be able to route it somewhere else, and it cannot if the two arrive as one
	 * code.
	 */
	public static final String DEVICE_STATE_UNKNOWN = "device.state.unknown";

	/** Used when the compiled process names no deadline; deployed configuration may override it. */
	private static final long DEFAULT_DEADLINE_MILLIS = 5_000L;

	private final DeviceCommandPort deviceCommandPort;

	@Override
	public void execute(DelegateExecution execution) {
		String laneExternalId = required(execution, ProcessVariables.LANE_EXTERNAL_ID);
		String action = required(execution, ProcessVariables.COMMAND_ACTION);
		// Required because the device id IS the device host's URL-path address.
		String deviceExternalId = required(execution, ProcessVariables.COMMAND_DEVICE_EXTERNAL_ID);
		long deadline = deadlineOf(execution);

		String outcome = deviceCommandPort.issue(new DeviceCommandPort.DeviceCommand(
				execution.getId(), laneExternalId, deviceExternalId, action, deadline));

		execution.setVariable(ProcessVariables.DEVICE_COMMAND_OUTCOME, outcome);

		if (DeviceCommandPort.UNKNOWN.equals(outcome)) {
			log.warn("device command {} on lane {} returned UNKNOWN — the physical state must be "
					+ "verified, not retried", execution.getId(), laneExternalId);
			throw new BpmnError(DEVICE_STATE_UNKNOWN,
					"The device host did not answer within the deadline. The barrier's actual state "
							+ "is unknown and must be verified rather than assumed.");
		}
		if (DeviceCommandPort.FAILED.equals(outcome)) {
			throw new BpmnError(DEVICE_COMMAND_FAILED, "The device host rejected the command.");
		}
	}

	private static long deadlineOf(DelegateExecution execution) {
		Object value = execution.getVariable(ProcessVariables.COMMAND_DEADLINE_MILLIS);
		if (value == null) {
			return DEFAULT_DEADLINE_MILLIS;
		}
		return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
	}

	private static String required(DelegateExecution execution, String name) {
		Object value = execution.getVariable(name);
		if (value == null || value.toString().isBlank()) {
			throw new IllegalStateException(
					"Process variable '" + name + "' is not set on activity '"
							+ execution.getCurrentActivityId() + "'. It is part of the BPMN execution "
							+ "profile and the compiled process is expected to carry it.");
		}
		return value.toString();
	}
}
