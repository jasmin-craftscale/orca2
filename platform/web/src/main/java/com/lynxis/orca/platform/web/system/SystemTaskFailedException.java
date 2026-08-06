package com.lynxis.orca.platform.web.system;

import lombok.Getter;

/**
 * Wraps a checked exception thrown by system work, so the identity that was
 * running is on the exception rather than only in a log line somewhere above it.
 */
@Getter
public class SystemTaskFailedException extends RuntimeException {

	private final transient SystemIdentity identity;

	public SystemTaskFailedException(SystemIdentity identity, Throwable cause) {
		super(identity + " failed", cause);
		this.identity = identity;
	}
}
