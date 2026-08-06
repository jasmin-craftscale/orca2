package com.lynxis.orca.platform.lease;

import lombok.Getter;

/**
 * A write was attempted by a holder that no longer holds the lease.
 *
 * <p>This is the mechanism working, not a fault. It is what a stalled instance
 * gets when it wakes up and tries to finish what it started, and it is thrown
 * rather than logged because silently dropping the write would leave the caller
 * believing it succeeded.
 */
@Getter
public class StaleFenceTokenException extends IllegalStateException {

	private final transient Lease presented;

	public StaleFenceTokenException(Lease presented) {
		super("Write refused: " + presented.holderId() + " presented fence token "
				+ presented.fenceToken() + " for lease '" + presented.leaseName()
				+ "', which it no longer holds. The lease expired or changed hands.");
		this.presented = presented;
	}
}
