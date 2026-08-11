package com.lynxis.orca.runtime.execution.api;

import com.lynxis.orca.platform.web.ErrorCode;

/**
 * The failures this module has that the platform's list does not cover.
 *
 * <p>{@code PlatformErrorCode} is deliberately framework-shaped and says so: it
 * contains nothing about visits, lanes, tickets, drivers or trucks, and a business
 * failure belongs in a service's own enum. This is that enum for
 * {@code execution}.
 */
public enum ExecutionErrorCode implements ErrorCode {

	/**
	 * An event named a lane this installation's site does not publish.
	 *
	 * <p>422 rather than 404: the request is well-formed and the route is right, and
	 * what is wrong is that this installation cannot act on it. A 404 would read as
	 * "no such endpoint" to a caller that is retrying a batch.
	 *
	 * <p>The batch is refused whole, so edge keeps it buffered and in order. An
	 * unmatched event must be made visible rather than dropped. Runtime exposes no
	 * dedicated operator view for it yet, so it stays in edge's buffer, is retried,
	 * and becomes {@code DEAD} there — bounded and visible through the buffer's
	 * diagnostics rather than nowhere.
	 */
	LANE_NOT_AT_THIS_INSTALLATION("LANE_NOT_AT_THIS_INSTALLATION", 422),

	/**
	 * No such visit under this installation's scope.
	 *
	 * <p>404 rather than 403, and the wording is deliberate: a visit belonging to
	 * another site and a visit that never existed are the same answer here. The scope
	 * seam makes them genuinely indistinguishable to this service — it applies the
	 * site condition before the filter, so the row is not read and then refused, it is
	 * never selected. Saying "exists, but not yours" would require reading it first,
	 * which is the thing the seam exists to prevent.
	 */
	VISIT_NOT_FOUND("VISIT_NOT_FOUND", 404),

	/**
	 * The {@code status} filter names something this platform does not have.
	 *
	 * <p>Refused rather than ignored, and 422 rather than 400 for the same reason the
	 * work-item team filter is: the request is well-formed and the route is right, and
	 * what is wrong is the value. Filtering on it anyway would answer {@code 200} with
	 * an empty list — and an empty list reads as "no visits at this gate" rather than
	 * "you asked for a status that does not exist", which is the reading that sends
	 * somebody looking for a fault in the gate.
	 */
	VISIT_FILTER_UNKNOWN_STATUS("VISIT_FILTER_UNKNOWN_STATUS", 422),

	/**
	 * The designer draft in the request body could not be read as a publish payload.
	 *
	 * <p>400, and only for the namespace route. The validate route deliberately
	 * answers {@code 200} with {@code compiles=false} instead — an unreadable draft
	 * is still an answer the builder can draw ("not publishable, and here is why"),
	 * but a namespace cannot be computed from a payload that has no graph, so there
	 * is nothing truthful to return but the refusal.
	 */
	DRAFT_UNREADABLE("DRAFT_UNREADABLE", 400);

	private final String code;
	private final int httpStatus;

	ExecutionErrorCode(String code, int httpStatus) {
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
