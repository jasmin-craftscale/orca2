package com.lynxis.orca.runtime.notify.api;

import com.lynxis.orca.platform.web.ErrorCode;

/** Failures owned by the operator notification surface. */
public enum NotificationErrorCode implements ErrorCode {

	/** The request has no platform operator identity. */
	OPERATOR_UNRESOLVED("OPERATOR_UNRESOLVED", 401),

	/** No such notification for this operator under the installation scope. */
	NOTIFICATION_NOT_FOUND("NOTIFICATION_NOT_FOUND", 404);

	private final String code;
	private final int httpStatus;

	NotificationErrorCode(String code, int httpStatus) {
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
