package com.lynxis.orca.edge.api;

import com.lynxis.orca.platform.web.ErrorCode;

/**
 * The failures this service has that the platform's list does not cover.
 *
 * <p>{@code PlatformErrorCode} is deliberately framework-shaped and says so: it
 * contains nothing about lanes or devices, and a business failure belongs in a
 * service's own enum.
 */
public enum EdgeErrorCode implements ErrorCode {

	/**
	 * The lane has no device host published for this installation's site.
	 *
	 * <p>422 rather than 404: the request is well formed and the route is right, and
	 * what is wrong is that this installation has nothing to command. A caller
	 * retrying a command needs to tell those apart — one is worth retrying after a
	 * configuration change, the other never is.
	 */
	LANE_HAS_NO_DEVICE_HOST("LANE_HAS_NO_DEVICE_HOST", 422);

	private final String code;
	private final int httpStatus;

	EdgeErrorCode(String code, int httpStatus) {
		this.code = code;
		this.httpStatus = httpStatus;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public int httpStatus() {
		return httpStatus;
	}
}
