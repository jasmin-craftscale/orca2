package com.lynxis.orca.platform.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one response shape, for every service, on every route (§B8, §D3).
 *
 * <p>Defined here and <em>implemented</em> here. The OpenAPI schema for it lives
 * beside this class in {@code src/main/resources/openapi/_shared.yaml}, and every
 * service's contract references that file rather than restating it. Seven
 * services each defining their own "error" object is precisely how a guarantee
 * decays into a convention.
 *
 * <p><strong>Why an envelope at all,</strong> given HTTP already has status codes:
 * because a caller needs to tell one failure from another without parsing prose,
 * and because a 400 that means "your JSON is malformed" and a 400 that means
 * "lane 7 is out of service" are different things a partner's integration must
 * branch on. {@link #code()} is that branch point.
 *
 * @param <T>       the payload type
 * @param status    result or failure — see {@link ApiStatus}
 * @param code      the machine-readable code. {@code "OK"} on success
 * @param message   human-readable, and never internal detail
 * @param data      the payload, absent on failure
 * @param errors    present when more than the top-level code is worth saying
 * @param page      present on paginated reads
 * @param requestId echoed back so a caller can quote it in a support request and
 *                  an operator can find the exact request in the log
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
		ApiStatus status,
		String code,
		String message,
		T data,
		List<ApiError> errors,
		PageMeta page,
		String requestId) {

	/** The success code. A caller that branches on {@code code} sees this and stops. */
	public static final String OK = "OK";

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(ApiStatus.SUCCESS, OK, null, data, null, null, RequestId.current());
	}

	public static <T> ApiResponse<T> ok(T data, PageMeta page) {
		return new ApiResponse<>(ApiStatus.SUCCESS, OK, null, data, null, page, RequestId.current());
	}

	public static <T> ApiResponse<T> error(ErrorCode code, String message) {
		return new ApiResponse<>(ApiStatus.ERROR, code.code(), message, null, null, null, RequestId.current());
	}

	public static <T> ApiResponse<T> error(ErrorCode code, String message, List<ApiError> errors) {
		return new ApiResponse<>(ApiStatus.ERROR, code.code(), message, null,
				errors == null || errors.isEmpty() ? null : List.copyOf(errors), null, RequestId.current());
	}
}
