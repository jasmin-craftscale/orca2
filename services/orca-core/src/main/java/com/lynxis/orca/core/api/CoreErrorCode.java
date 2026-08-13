package com.lynxis.orca.core.api;

import com.lynxis.orca.platform.web.ErrorCode;

/**
 * orca-core's own error codes, alongside the platform set.
 *
 * <p>Each is a machine-readable branch point a console can act on without prose:
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
	TEMPLATE_IN_USE("TEMPLATE_IN_USE", 409),

	/** A device type code is not in the seeded catalog. */
	DEVICE_TYPE_UNKNOWN("DEVICE_TYPE_UNKNOWN", 422),

	/** An IO port name code is not in the seeded catalog. */
	PORT_NAME_UNKNOWN("PORT_NAME_UNKNOWN", 422),

	/** A settings key is not in the registry of known keys. */
	SETTING_UNKNOWN("SETTING_UNKNOWN", 422),

	/** A secret-shaped settings key — secrets never enter the settings table (rule 8). */
	SETTING_SECRET_REJECTED("SETTING_SECRET_REJECTED", 422),

	/** A grid code is not in the catalog. */
	GRID_UNKNOWN("GRID_UNKNOWN", 422),

	/** The authenticated token maps to no active platform user. */
	USER_NOT_LINKED("USER_NOT_LINKED", 403),

	SCREEN_UNKNOWN("SCREEN_UNKNOWN", 422),

	/** An active screen already fronts that node at this site — routing must resolve to ONE screen. */
	SCREEN_NODE_TAKEN("SCREEN_NODE_TAKEN", 409),

	LANE_UNKNOWN("LANE_UNKNOWN", 422),

	/** The submitted rule set names the same (screen, lane) pair twice. */
	ROUTING_RULE_DUPLICATE("ROUTING_RULE_DUPLICATE", 422),

	/** A custom-entity declaration violates its closed identifier, key or type-shape rules. */
	CUSTOM_ENTITY_DECLARATION_INVALID("CUSTOM_ENTITY_DECLARATION_INVALID", 422);

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
