package com.lynxis.orca.platform.lease;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * How long a lease is held, and how often its holder renews it.
 *
 * <p><strong>Neither has a default, deliberately.</strong> No universal duration,
 * renewal interval or clock-skew allowance has been chosen. The first two are
 * installation configuration that must be set explicitly, and <em>a service
 * refuses to start when either is unset or when the duration is not longer than
 * the renewal interval</em>. {@link LeaseConfigurationValidator} enforces that
 * startup rule.
 *
 * <p>A default here would satisfy the validator and defeat the point: an
 * installation would run on a number nobody chose, and the first sign of it being
 * wrong is a lane with two owners.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "orca.lease")
public class LeaseProperties {

	/** How long an acquisition is valid. Must exceed {@link #renewalInterval}. */
	private Duration duration;

	/**
	 * How often a holder renews.
	 *
	 * <p>If this is not comfortably shorter than {@link #duration}, a holder loses
	 * its lease between renewals under ordinary jitter — and the failure looks like
	 * intermittent ownership flapping rather than a configuration mistake.
	 */
	private Duration renewalInterval;
}
