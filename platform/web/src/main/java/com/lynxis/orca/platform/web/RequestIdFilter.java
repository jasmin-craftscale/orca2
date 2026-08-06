package com.lynxis.orca.platform.web;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Establishes a request id for every request, and clears it afterwards.
 *
 * <p>A caller's own {@code X-Request-Id} is honoured so a trace crosses service
 * boundaries; one is generated when absent. It goes into the response header, the
 * envelope and the logging context, so the same string appears in all three.
 *
 * <p>The {@code finally} block is the load-bearing part: this runs on a pooled
 * request thread, and a value left behind is a value the next request inherits.
 */
public class RequestIdFilter extends OncePerRequestFilter {

	/** The MDC key, so every log line inside a request carries it without being told to. */
	public static final String MDC_KEY = "requestId";

	/**
	 * A caller-supplied id is bounded before it is used: it reaches a response
	 * header and the log, and an unbounded caller-controlled string in either is
	 * not something to inherit.
	 */
	private static final int MAX_LENGTH = 128;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String supplied = request.getHeader(RequestId.HEADER);
		String requestId = isUsable(supplied) ? supplied : RequestId.generate();

		RequestId.set(requestId);
		MDC.put(MDC_KEY, requestId);
		response.setHeader(RequestId.HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
			RequestId.clear();
		}
	}

	private static boolean isUsable(String supplied) {
		if (supplied == null || supplied.isBlank() || supplied.length() > MAX_LENGTH) {
			return false;
		}
		for (int i = 0; i < supplied.length(); i++) {
			char c = supplied.charAt(i);
			boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
			if (!allowed) {
				return false;
			}
		}
		return true;
	}
}
