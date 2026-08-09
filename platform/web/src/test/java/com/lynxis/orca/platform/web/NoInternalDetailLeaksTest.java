package com.lynxis.orca.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that no error path serialises an exception or a schema name.
 *
 * <p>This does not test that the handler "returns a 500 for an exception"; that is
 * exercising the path. It feeds each handler an exception whose message contains
 * exactly the things that must never reach a caller — a schema-qualified table
 * name, a SQL statement, a stack frame, an internal host, a file path — serialises
 * the response the handler produced, and asserts none of it survives.
 *
 * <p>The exceptions used are the real ones the framework throws, not stand-ins.
 */
class NoInternalDetailLeaksTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Every one of these appears in a real exception message from this stack, and
	 * every one of them tells a caller something about the inside of the service.
	 */
	private static final List<String> FORBIDDEN = List.of(
			"core.outbox",
			"runtime.service_lease",
			"flyway_schema_history",
			"SELECT publish_seq FROM",
			"com.microsoft.sqlserver.jdbc",
			"org.hibernate",
			"com.lynxis.orca.core.persistence",
			"at com.lynxis.orca",
			"/opt/orca/config/application.yaml",
			"orca-sqlserver.internal",
			"Caused by",
			"sa_password");

	private static String poisoned() {
		return "The SELECT permission was denied on the object 'flyway_schema_history', "
				+ "database 'orca', schema 'core.outbox' while running "
				+ "SELECT publish_seq FROM runtime.service_lease "
				+ "via com.microsoft.sqlserver.jdbc.SQLServerException "
				+ "at com.lynxis.orca.core.persistence.Repo (org.hibernate) "
				+ "config /opt/orca/config/application.yaml host orca-sqlserver.internal "
				+ "Caused by sa_password=hunter2";
	}

	static Stream<Throwable> everyKindOfFailure() {
		return Stream.of(
				new IllegalStateException(poisoned()),
				new RuntimeException(poisoned(), new IllegalArgumentException(poisoned())),
				new NullPointerException(poisoned()),
				unreadable(poisoned()),
				new BadCredentialsException(poisoned()),
				new AccessDeniedException(poisoned()),
				new java.sql.SQLException(poisoned()),
				new OutOfMemoryError(poisoned()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("everyKindOfFailure")
	@DisplayName("no handler puts internal detail on the wire, whatever it is given")
	void nothingInternalReachesTheWire(Throwable thrown) {
		ResponseEntity<ApiResponse<Void>> response = dispatch(thrown);
		String body = json.writeValueAsString(response.getBody());

		assertThat(body).isNotBlank();
		for (String secret : FORBIDDEN) {
			assertThat(body)
					.as("serialised response must not contain %s — it came from the exception", secret)
					.doesNotContain(secret);
		}
		// The exception's own type is internal too: "NullPointerException" tells a
		// prober that they found an unguarded path.
		assertThat(body).doesNotContain(thrown.getClass().getSimpleName());
		assertThat(body).doesNotContain(thrown.getClass().getName());
	}

	@Test
	@DisplayName("the message a service chooses IS returned — the handler is not simply blanking everything")
	void aDeliberateMessageSurvives() {
		var response = handler.handleApi(
				new ApiException(PlatformErrorCode.CONFLICT, "That item is already claimed.",
						List.of(), new IllegalStateException(poisoned())));

		String body = json.writeValueAsString(response.getBody());
		assertThat(body).contains("That item is already claimed.");
		assertThat(body).contains("CONFLICT");
		// ...and the cause it was constructed with still does not leak.
		assertThat(body).doesNotContain("service_lease");
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

	private ResponseEntity<ApiResponse<Void>> dispatch(Throwable thrown) {
		return switch (thrown) {
			case HttpMessageNotReadableException e -> handler.handleUnreadable(e);
			case BadCredentialsException e -> handler.handleAuthentication(e);
			case AccessDeniedException e -> handler.handleAccessDenied(e);
			default -> handler.handleAnythingElse(thrown);
		};
	}
}
