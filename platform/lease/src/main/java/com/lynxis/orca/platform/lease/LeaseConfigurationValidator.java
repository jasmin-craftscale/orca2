package com.lynxis.orca.platform.lease;

import org.springframework.beans.factory.InitializingBean;

import lombok.RequiredArgsConstructor;

/**
 * Refuses to start a service whose lease configuration is unset or incoherent.
 *
 * <p>This class <em>is</em> the specification. §C2 states no duration, no renewal
 * interval and no clock-skew allowance, and says instead that a service refuses to
 * start when either is unset or when expiry is not greater than the renewal
 * interval. So that is what happens here, and no number is invented to make it
 * pass.
 *
 * <p>Why refuse rather than warn: a renewal interval at or above the lease
 * duration means the holder's renewal always arrives after the lease has already
 * expired. Another instance can take it in between, and both then believe they
 * own it. Nothing about that is visible in a log until two instances write as
 * owner.
 */
@RequiredArgsConstructor
public class LeaseConfigurationValidator implements InitializingBean {

	private final LeaseProperties properties;

	@Override
	public void afterPropertiesSet() {
		if (properties.getDuration() == null) {
			throw new IllegalStateException(unset("orca.lease.duration"));
		}
		if (properties.getRenewalInterval() == null) {
			throw new IllegalStateException(unset("orca.lease.renewal-interval"));
		}
		if (properties.getDuration().isZero() || properties.getDuration().isNegative()) {
			throw new IllegalStateException("orca.lease.duration must be positive; it is "
					+ properties.getDuration());
		}
		if (properties.getRenewalInterval().isZero() || properties.getRenewalInterval().isNegative()) {
			throw new IllegalStateException("orca.lease.renewal-interval must be positive; it is "
					+ properties.getRenewalInterval());
		}
		if (properties.getDuration().compareTo(properties.getRenewalInterval()) <= 0) {
			throw new IllegalStateException(
					"orca.lease.duration (" + properties.getDuration() + ") must be LONGER than "
							+ "orca.lease.renewal-interval (" + properties.getRenewalInterval() + "). "
							+ "As configured, a holder's renewal always arrives after its lease has "
							+ "already expired, so another instance can take it in between and both "
							+ "will believe they own it.");
		}
	}

	private static String unset(String property) {
		return property + " is not set, and there is no default. "
				+ "Lease timing is profile configuration that must be chosen deliberately: "
				+ "a value nobody picked is a value nobody will question when a lane ends up "
				+ "with two owners.";
	}
}
