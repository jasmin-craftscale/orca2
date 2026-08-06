package com.lynxis.orca.platform.web;

/**
 * The failures every service can have, regardless of what it does.
 *
 * <p>Each value is a <em>distinct</em> code, because two failures a caller must
 * handle differently cannot share one. That is asserted by a test rather than
 * left to review.
 *
 * <p>This list is deliberately short and framework-shaped. It contains nothing
 * about visits, lanes, tickets, drivers or trucks, and it is not the place to add
 * a business failure — a service defines its own enum implementing
 * {@link ErrorCode} for those.
 */
public enum PlatformErrorCode implements ErrorCode {

	/** The request could not be parsed at all — malformed JSON, a bad content type. */
	REQUEST_MALFORMED("REQUEST_MALFORMED", 400),

	/** The request parsed but failed validation. Field-level detail is in {@code errors}. */
	VALIDATION_FAILED("VALIDATION_FAILED", 400),

	/** No credential, or one that did not verify. */
	UNAUTHENTICATED("UNAUTHENTICATED", 401),

	/** A verified caller that is not entitled to this. */
	FORBIDDEN("FORBIDDEN", 403),

	/** The addressed thing does not exist, or is outside the caller's scope. */
	NOT_FOUND("NOT_FOUND", 404),

	/** The route exists but not for this method. */
	METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", 405),

	/**
	 * A guarded conditional update found the row already changed — a lost race.
	 *
	 * <p>The loser of a race receives this and can act on it. It is never a silent
	 * no-op, which is the failure mode the work-item claim exists to avoid.
	 */
	CONFLICT("CONFLICT", 409),

	/** The caller's payload is larger than this service accepts. */
	PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", 413),

	/** A dependency did not answer inside its deadline. Every external call has one (§B8). */
	DEADLINE_EXCEEDED("DEADLINE_EXCEEDED", 504),

	/** A dependency is down or its circuit is open. */
	DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE", 503),

	/**
	 * Anything unhandled.
	 *
	 * <p>The message served with this code is a fixed string. It never carries the
	 * exception's text, and that is the single most important line in this file:
	 * an unhandled failure is exactly where a stack trace, a SQL statement or a
	 * schema name would otherwise reach a caller.
	 */
	INTERNAL_ERROR("INTERNAL_ERROR", 500);

	private final String code;
	private final int httpStatus;

	PlatformErrorCode(String code, int httpStatus) {
		this.code = code;
		this.httpStatus = httpStatus;
	}

	// Written out rather than generated: ErrorCode declares record-style accessors
	// (`code()`, not `getCode()`), and Lombok's @Getter would not implement them.
	@Override
	public String code() {
		return code;
	}

	@Override
	public int httpStatus() {
		return httpStatus;
	}
}
