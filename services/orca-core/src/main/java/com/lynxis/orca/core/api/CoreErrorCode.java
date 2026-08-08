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
	ROLE_IN_USE("ROLE_IN_USE", 409),

	/** A timezone is not an IANA zone id the platform's tz database knows. */
	TIME_ZONE_UNKNOWN("TIME_ZONE_UNKNOWN", 422),

	/** A named shift template does not exist or is retired. */
	SHIFT_TEMPLATE_UNKNOWN("SHIFT_TEMPLATE_UNKNOWN", 422),

	/** A named break template does not exist or is retired. */
	BREAK_TEMPLATE_UNKNOWN("BREAK_TEMPLATE_UNKNOWN", 422),

	/** A named user does not exist or is retired. */
	USER_UNKNOWN("USER_UNKNOWN", 422),

	/** A template cannot be retired while active teams reference it. */
	TEMPLATE_IN_USE("TEMPLATE_IN_USE", 409);

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
