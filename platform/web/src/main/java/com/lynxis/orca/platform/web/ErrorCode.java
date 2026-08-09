package com.lynxis.orca.platform.web;

/**
 * A machine-readable error code a caller can branch on <em>without reading the
 * message</em>.
 *
 * <p>That is the whole point, and it is why this is not a string typed at each
 * throw site: a partner's integration branches on the code, so improving the
 * wording of a message must never break it.
 *
 * <p><strong>An interface rather than an enum, on purpose.</strong> The platform
 * owns the codes every service needs — see {@link PlatformErrorCode} — and each
 * service adds its own enum implementing this for the failures only it can have.
 * An enum here would either be closed, forcing services to reuse a code that does
 * not describe their failure, or it would grow until it named business concepts,
 * which is exactly what {@code platform/} must not do.
 *
 * <p><strong>What is deliberately not here:</strong> the partner event API's
 * validation taxonomy. That taxonomy remains open for a narrow design by the
 * service that serves the partner API; defining it in this shared envelope would
 * prematurely impose one service's business failures on every other service.
 */
public interface ErrorCode {

	/**
	 * The stable wire value. {@code SCREAMING_SNAKE_CASE}, and never reused for a
	 * different meaning once a caller may have branched on it.
	 */
	String code();

	/**
	 * The HTTP status this code is served with.
	 *
	 * <p>The code is the contract; the status is a transport detail that lets an
	 * ordinary HTTP client behave sensibly without understanding the code.
	 */
	int httpStatus();
}
