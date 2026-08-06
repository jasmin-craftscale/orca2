package com.lynxis.orca.platform.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps every failure to the envelope, and lets none of them carry internal detail.
 *
 * <p>The rule this class exists to hold is one line long: <strong>no response
 * body produced here is derived from an exception's message, its class name, its
 * stack trace, or any database identifier.</strong> Every client-facing string
 * below is a literal in this file or a value a service chose deliberately by
 * throwing {@link ApiException}.
 *
 * <p>The reason is not tidiness. Exception text is where schema names, SQL
 * fragments, file paths and internal host names reach a caller — and the caller
 * that most wants them is the one that should have them least.
 *
 * <p>Everything unhandled is logged in full, at the boundary, with the request id
 * — so nothing is lost, it is simply not sent to the caller.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final String INTERNAL_MESSAGE =
			"The request could not be completed. Quote the request id when reporting this.";

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex) {
		log.warn("{} -> {}", RequestId.current(), ex.getMessage(), ex);
		return respond(ex.getErrorCode(), ex.getClientMessage(), ex.getErrors());
	}

	// --- Bad requests --------------------------------------------------------

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleInvalidBody(MethodArgumentNotValidException ex) {
		List<ApiError> errors = new ArrayList<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			// The field name and the validation message are the CONTRACT's, not the
			// framework's internals — they come from the annotation on the DTO.
			errors.add(ApiError.field(PlatformErrorCode.VALIDATION_FAILED,
					error.getField(), error.getDefaultMessage()));
		}
		return respond(PlatformErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", errors);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiResponse<Void>> handleInvalidParameters(HandlerMethodValidationException ex) {
		log.debug("{} -> parameter validation failed", RequestId.current(), ex);
		return respond(PlatformErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.", List.of());
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
		List<ApiError> errors = new ArrayList<>();
		for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
			String path = violation.getPropertyPath() == null ? null : violation.getPropertyPath().toString();
			errors.add(ApiError.field(PlatformErrorCode.VALIDATION_FAILED, path, violation.getMessage()));
		}
		return respond(PlatformErrorCode.VALIDATION_FAILED, "One or more values are invalid.", errors);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
		return respond(PlatformErrorCode.VALIDATION_FAILED, "A required parameter is missing.",
				List.of(ApiError.field(PlatformErrorCode.VALIDATION_FAILED, ex.getParameterName(), "is required")));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
		// Jackson's message names Java classes and, for a nested failure, package
		// paths. It is logged and never returned.
		log.debug("{} -> unreadable request body", RequestId.current(), ex);
		return respond(PlatformErrorCode.REQUEST_MALFORMED, "The request body could not be read.", List.of());
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
		log.debug("{} -> method not allowed", RequestId.current(), ex);
		return respond(PlatformErrorCode.METHOD_NOT_ALLOWED, "That method is not allowed on this route.", List.of());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
		log.debug("{} -> unsupported media type", RequestId.current(), ex);
		return respond(PlatformErrorCode.REQUEST_MALFORMED, "That content type is not supported.", List.of());
	}

	@ExceptionHandler(NoHandlerFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
		log.debug("{} -> no handler", RequestId.current(), ex);
		return respond(PlatformErrorCode.NOT_FOUND, "No such route.", List.of());
	}

	@ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(
			org.springframework.web.servlet.resource.NoResourceFoundException ex) {
		// Boot 4's resource handling throws THIS for an unmatched route, not
		// NoHandlerFoundException. Found live: without this handler an unknown path
		// fell through to the catch-all and came back as INTERNAL_ERROR 500 — an
		// alarming answer to a typo.
		log.debug("{} -> no resource", RequestId.current(), ex);
		return respond(PlatformErrorCode.NOT_FOUND, "No such route.", List.of());
	}

	// --- Identity ------------------------------------------------------------

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
		// Never says WHY: "expired", "bad signature" and "wrong issuer" are three
		// facts an attacker probing a token would like to have.
		log.debug("{} -> authentication failed", RequestId.current(), ex);
		return respond(PlatformErrorCode.UNAUTHENTICATED, "Authentication is required.", List.of());
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
		log.debug("{} -> access denied", RequestId.current(), ex);
		return respond(PlatformErrorCode.FORBIDDEN, "You are not entitled to this.", List.of());
	}

	// --- The catch-all -------------------------------------------------------

	/**
	 * Everything else. Logged in full; served as a fixed string.
	 *
	 * <p>This is the handler that matters. Any exception a service did not
	 * anticipate lands here — a JDBC error naming a schema, a Hibernate error
	 * naming an entity, a null pointer naming a class — and none of it goes out.
	 */
	@ExceptionHandler(Throwable.class)
	public ResponseEntity<ApiResponse<Void>> handleAnythingElse(Throwable ex) {
		log.error("{} -> unhandled {}", RequestId.current(), ex.getClass().getName(), ex);
		return respond(PlatformErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE, List.of());
	}

	private ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message, List<ApiError> errors) {
		return ResponseEntity.status(HttpStatus.valueOf(code.httpStatus()))
				.body(ApiResponse.error(code, message, errors));
	}
}
