package com.lynxis.orca.runtime.execution.api;

import java.util.Optional;

/**
 * The seam through which {@code workitem} learns which visit is running on a lane.
 *
 * <p>Finding a lane's active visit is {@code execution}'s knowledge, and the module
 * wall forbids {@code workitem} from reading its tables. This interface is the
 * narrow, deliberate crossing — the same shape as {@code ManualStepPort} in the
 * other direction.
 */
public interface LaneVisitPort {

	/**
	 * The execution id of the visit running on this lane.
	 *
	 * @return empty when the lane is clear
	 * @throws LaneNotPublishedException when this installation does not publish the
	 *         lane — a different fact from "the lane is clear", and the caller
	 *         answers them differently
	 */
	Optional<Long> activeVisitOn(String laneExternalId) throws LaneNotPublishedException;

	/**
	 * This installation does not publish that lane.
	 *
	 * <p><strong>Declared here, on the port, and not reused from {@code execution}'s
	 * domain.</strong> An exception a caller must catch is part of the contract, so a
	 * port that throws a domain type has not hidden the module — the caller ends up
	 * importing {@code execution.domain} to write the catch clause, and
	 * {@code ModuleWallRule} refuses it. {@code ManualStepPort.ProcessNotWaitingException}
	 * is the same shape for the same reason.
	 */
	class LaneNotPublishedException extends RuntimeException {

		public LaneNotPublishedException(String laneExternalId) {
			super("Lane '" + laneExternalId + "' is not published by this installation.");
		}
	}
}
