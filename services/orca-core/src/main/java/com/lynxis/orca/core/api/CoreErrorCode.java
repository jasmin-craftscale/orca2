package com.lynxis.orca.core.api;

import com.lynxis.orca.platform.web.ErrorCode;

/**
 * orca-core's own error codes, alongside the platform set.
 *
 * <p>Each is a branch point a console can act on without reading prose (§B8):
 * "that role does not exist" and "that entitlement code does not exist" lead an
 * administrator to two different screens.
 */
public enum CoreErrorCode implements ErrorCode {

	/** A named role does not exist or is retired. */
	ROLE_UNKNOWN("ROLE_UNKNOWN", 422),

	/** An entitlement code is not in the seeded catalog. */
	ENTITLEMENT_UNKNOWN("ENTITLEMENT_UNKNOWN", 422),

	/** A named site does not exist at this installation. */
	SITE_UNKNOWN("SITE_UNKNOWN", 422),

	/** A role cannot be retired while active users hold it. */
	ROLE_IN_USE("ROLE_IN_USE", 409);

	private final String code;
	private final int httpStatus;

	CoreErrorCode(String code, int httpStatus) {
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
