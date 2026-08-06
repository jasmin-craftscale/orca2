package com.lynxis.orca.platform.web;

/**
 * Whether a response carries a result or a failure.
 *
 * <p>Two values, deliberately. A caller branches on {@link ApiResponse#code()} to
 * tell one failure from another; this field only says which kind of body to read.
 */
public enum ApiStatus {
	SUCCESS,
	ERROR
}
