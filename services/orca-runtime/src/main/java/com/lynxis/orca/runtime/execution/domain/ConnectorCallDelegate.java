package com.lynxis.orca.runtime.execution.domain;

import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.DelegateExecution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The compiled process's link target for "call a customer system".
 *
 * <p><strong>The bean name is an API.</strong> {@code ${connectorCallDelegate}} is
 * what the visual builder's compiler emits, for every connector step in every
 * process any administrator ever designs. So it is generic and it does not move:
 * one delegate parameterised by variables, never one delegate per connector.
 * {@code ${acmeTosDelegate}} would be a bean name that has to be invented at
 * compile time, and a compiler that invents bean names is a compiler that can emit
 * a process nothing can run.
 *
 * <p>It reads correlation keys, calls the port, and writes back one token. It does
 * not touch a database, does not know what a TOS is, and does not decide what the
 * answer means — that is the gateway's job, and the routing is configuration
 * (§C2), not code.
 */
@Slf4j
@RequiredArgsConstructor
public class ConnectorCallDelegate implements JavaDelegate {

	/** The BPMN error code the failure branch catches. Part of the profile. */
	public static final String CONNECTOR_FAILED = "connector.failed";

	private final ConnectorPort connectorPort;

	@Override
	public void execute(DelegateExecution execution) {
		String visitExternalId = required(execution, ProcessVariables.VISIT_EXTERNAL_ID);
		String laneExternalId = required(execution, ProcessVariables.LANE_EXTERNAL_ID);
		String connectorName = required(execution, ProcessVariables.CONNECTOR_NAME);

		try {
			String outcome = connectorPort.call(
					new ConnectorPort.ConnectorCall(visitExternalId, laneExternalId, connectorName));
			execution.setVariable(ProcessVariables.CONNECTOR_OUTCOME, outcome);
		}
		catch (ConnectorPort.ConnectorUnavailableException unavailable) {
			// A BpmnError, not a runtime exception. A runtime exception rolls the job
			// back and retries it on the async executor, which for a customer system
			// that is down means retrying into a wall; a BpmnError is caught by the
			// boundary event and the visit reaches a human, which is the outcome
			// somebody can act on.
			log.warn("connector '{}' unavailable for visit {}: {}",
					connectorName, visitExternalId, unavailable.getMessage());
			throw new BpmnError(CONNECTOR_FAILED, unavailable.getMessage());
		}
	}

	private static String required(DelegateExecution execution, String name) {
		Object value = execution.getVariable(name);
		if (value == null || value.toString().isBlank()) {
			// A missing correlation key is a compiler defect, not a runtime
			// condition, so it fails loudly rather than taking a business branch.
			throw new IllegalStateException(
					"Process variable '" + name + "' is not set on activity '"
							+ execution.getCurrentActivityId() + "'. It is part of the BPMN execution "
							+ "profile and the compiled process is expected to carry it.");
		}
		return value.toString();
	}
}
