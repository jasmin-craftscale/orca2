package com.lynxis.orca.platform.web;

import java.util.UUID;

/**
 * The identifier carried on every response and every log line for one request.
 *
 * <p>Ambient rather than threaded through every signature, because a request id
 * that has to be passed explicitly is one that gets dropped at the first
 * boundary — and it is most needed exactly where it was dropped.
 */
public final class RequestId {

	/** The header a caller may supply, and the one this service echoes. */
	public static final String HEADER = "X-Request-Id";

	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	private RequestId() {
	}

	/** The current request's id, or {@code null} outside a request. */
	public static String current() {
		return CURRENT.get();
	}

	static void set(String requestId) {
		CURRENT.set(requestId);
	}

	static void clear() {
		CURRENT.remove();
	}

	static String generate() {
		return UUID.randomUUID().toString();
	}
}
