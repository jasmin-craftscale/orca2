package com.lynxis.orca.runtime.workitem.api;

import com.lynxis.orca.platform.web.ErrorCode;

/**
 * The failures this module has that the platform's list does not cover — the
 * {@code workitem} sibling of {@code ExecutionErrorCode}.
 */
public enum WorkItemErrorCode implements ErrorCode {

	/** No such item under this installation's scope. */
	WORK_ITEM_NOT_FOUND("WORK_ITEM_NOT_FOUND", 404),

	/**
	 * The guarded conditional update moved 0 rows — somebody else acted first, or
	 * the item is not in a state the action applies to. The message carries the
	 * item's current state so the console can show what actually happened; a 1.x
	 * silent no-op is exactly what this refuses to be.
	 */
	WORK_ITEM_CONFLICT("WORK_ITEM_CONFLICT", 409),

	/**
	 * The engine is not waiting on this item's step (inversion 3 of the sheet's
	 * §0). Distinguished from {@code WORK_ITEM_CONFLICT} deliberately: a conflict
	 * means another operator moved the item; this means the <em>process</em> moved
	 * on — reset, already advanced, or never parked there — and the console should
	 * refresh the visit, not just the queue row.
	 */
	WORK_ITEM_OUT_OF_ORDER("WORK_ITEM_OUT_OF_ORDER", 409),

	/**
	 * The operator is outside the item's eligible teams (WP2 — the claim respects
	 * the routing rules). 403, not 409: nothing raced, the claim was never theirs.
	 */
	WORK_ITEM_NOT_ELIGIBLE("WORK_ITEM_NOT_ELIGIBLE", 403),

	/**
	 * The request is authenticated but carries no resolvable operator identity.
	 * Every work-item action needs an actor — an audit trail with a hole in it is
	 * the 1.x shape this module refuses.
	 */
	OPERATOR_UNRESOLVED("OPERATOR_UNRESOLVED", 401),

	/**
	 * {@code teamExternalId} was combined with a terminal status. The team filter
	 * is an open-queue concept (routing rules govern live work); silently ignoring
	 * it would present every team's history as one team's. Refused, stated.
	 */
	TEAM_FILTER_IS_OPEN_QUEUE_ONLY("TEAM_FILTER_IS_OPEN_QUEUE_ONLY", 422);

	private final String code;
	private final int httpStatus;

	WorkItemErrorCode(String code, int httpStatus) {
		this.code = code;
		this.httpStatus = httpStatus;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public int httpStatus() {
		return httpStatus;
	}
}
