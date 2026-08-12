package com.lynxis.orca.runtime.readmodel.api;

import com.lynxis.orca.platform.web.ErrorCode;

/** Failures owned by readmodel's console-grid surface. */
public enum ReadModelErrorCode implements ErrorCode {

	/** No such export under this installation's scope. */
	GRID_EXPORT_NOT_FOUND("GRID_EXPORT_NOT_FOUND", 404);

	private final String code;
	private final int httpStatus;

	ReadModelErrorCode(String code, int httpStatus) {
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
