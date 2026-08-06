package com.lynxis.orca.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One error inside the envelope's {@code errors} array.
 *
 * <p>A response carries several of these when several things are wrong at once —
 * a validation failure over four fields is four entries, not four round trips.
 *
 * @param code    the machine-readable code for <em>this</em> error, which may be
 *                more specific than the envelope's top-level code
 * @param field   the input this error is about, or {@code null} when it is about
 *                the request as a whole
 * @param message a human-readable explanation. Written for a developer reading a
 *                log, and <strong>never</strong> derived from an exception's text
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String field, String message) {

	public static ApiError of(ErrorCode code, String message) {
		return new ApiError(code.code(), null, message);
	}

	public static ApiError field(ErrorCode code, String field, String message) {
		return new ApiError(code.code(), field, message);
	}
}
