package com.lynxis.orca.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * P5 property 3 — <strong>two distinct failures return two distinct codes.</strong>
 *
 * <p>The point is not that codes exist. It is that a caller can branch on them
 * <em>without reading the message</em>: if two failures a partner must handle
 * differently share a code, the partner is forced back to parsing prose, and then
 * improving a message breaks their integration.
 */
class DistinctErrorCodesTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	@DisplayName("no two PlatformErrorCode values share a wire code")
	void everyPlatformCodeIsDistinct() {
		Map<String, PlatformErrorCode> byCode = new HashMap<>();
		for (PlatformErrorCode code : PlatformErrorCode.values()) {
			PlatformErrorCode clash = byCode.put(code.code(), code);
			assertThat(clash)
					.as("%s and %s both use the wire code '%s'", clash, code, code.code())
					.isNull();
		}
		assertThat(byCode).hasSize(PlatformErrorCode.values().length);
	}

	@Test
	@DisplayName("distinct failures reaching the handler come back with distinct codes")
	void distinctFailuresProduceDistinctCodes() {
		String malformed = handler.handleUnreadable(unreadable("x"))
				.getBody().code();
		String unauthenticated = handler.handleAuthentication(new BadCredentialsException("x"))
				.getBody().code();
		String forbidden = handler.handleAccessDenied(new AccessDeniedException("x"))
				.getBody().code();
		String internal = handler.handleAnythingElse(new IllegalStateException("x"))
				.getBody().code();
		String conflict = handler.handleApi(new ApiException(PlatformErrorCode.CONFLICT, "claimed"))
				.getBody().code();

		assertThat(List.of(malformed, unauthenticated, forbidden, internal, conflict))
				.as("five different failures, five different codes")
				.doesNotHaveDuplicates();
	}


	/**
	 * Spring 7 dropped {@code HttpMessageNotReadableException(String)}; the input
	 * message is now required. Built here rather than mocked so the test uses the
	 * real exception the framework throws.
	 */
	private static HttpMessageNotReadableException unreadable(String message) {
		return new HttpMessageNotReadableException(message, new org.springframework.http.HttpInputMessage() {
			@Override
			public java.io.InputStream getBody() {
				return java.io.InputStream.nullInputStream();
			}

			@Override
			public org.springframework.http.HttpHeaders getHeaders() {
				return new org.springframework.http.HttpHeaders();
			}
		});
	}

	@Test
	@DisplayName("a success is distinguishable from a failure without reading the message")
	void successCarriesItsOwnCode() {
		ApiResponse<String> ok = ApiResponse.ok("payload");
		assertThat(ok.status()).isEqualTo(ApiStatus.SUCCESS);
		assertThat(ok.code()).isEqualTo(ApiResponse.OK);
		assertThat(ok.errors()).isNull();

		ApiResponse<Void> failed = ApiResponse.error(PlatformErrorCode.NOT_FOUND, "no such thing");
		assertThat(failed.status()).isEqualTo(ApiStatus.ERROR);
		assertThat(failed.code()).isNotEqualTo(ApiResponse.OK);
		assertThat(failed.data()).isNull();
	}

	@Test
	@DisplayName("each code carries an HTTP status, so an ordinary client behaves sensibly without understanding it")
	void everyCodeHasAUsableHttpStatus() {
		for (PlatformErrorCode code : PlatformErrorCode.values()) {
			assertThat(code.httpStatus())
					.as("%s", code)
					.isBetween(400, 599);
		}
	}
}
