package com.lynxis.orca.platform.lease;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §C2 states no lease duration, no renewal interval and no clock-skew allowance,
 * and says instead that <em>a service refuses to start when either is unset or when
 * expiry is not greater than the renewal interval</em>. That startup validation is
 * the specification, so this is the test of the specification.
 */
class LeaseConfigurationValidatorTest {

	@Test
	@DisplayName("a service with no lease duration configured refuses to start")
	void unsetDurationRefusesStartup() {
		LeaseProperties properties = new LeaseProperties();
		properties.setRenewalInterval(Duration.ofSeconds(5));

		assertThatThrownBy(() -> new LeaseConfigurationValidator(properties).afterPropertiesSet())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("orca.lease.duration is not set")
				.hasMessageContaining("no default");
	}

	@Test
	@DisplayName("a service with no renewal interval configured refuses to start")
	void unsetRenewalIntervalRefusesStartup() {
		LeaseProperties properties = new LeaseProperties();
		properties.setDuration(Duration.ofSeconds(30));

		assertThatThrownBy(() -> new LeaseConfigurationValidator(properties).afterPropertiesSet())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("orca.lease.renewal-interval is not set");
	}

	@Test
	@DisplayName("a renewal interval at or above the lease duration refuses to start, and says why")
	void renewalNotShorterThanDurationRefusesStartup() {
		// This is the configuration that looks fine and produces two owners: the
		// holder's renewal always arrives after its lease has already expired, so
		// another instance can take it in between.
		for (Duration renewal : new Duration[] { Duration.ofSeconds(30), Duration.ofSeconds(45) }) {
			LeaseProperties properties = new LeaseProperties();
			properties.setDuration(Duration.ofSeconds(30));
			properties.setRenewalInterval(renewal);

			assertThatThrownBy(() -> new LeaseConfigurationValidator(properties).afterPropertiesSet())
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("must be LONGER than")
					.hasMessageContaining("both will believe they own it");
		}
	}

	@Test
	@DisplayName("a zero or negative duration refuses to start")
	void nonsensicalDurationsRefuseStartup() {
		for (Duration bad : new Duration[] { Duration.ZERO, Duration.ofSeconds(-1) }) {
			LeaseProperties properties = new LeaseProperties();
			properties.setDuration(bad);
			properties.setRenewalInterval(Duration.ofSeconds(1));

			assertThatThrownBy(() -> new LeaseConfigurationValidator(properties).afterPropertiesSet())
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("must be positive");
		}
	}

	@Test
	@DisplayName("a coherent configuration starts")
	void aCoherentConfigurationIsAccepted() {
		LeaseProperties properties = new LeaseProperties();
		properties.setDuration(Duration.ofSeconds(30));
		properties.setRenewalInterval(Duration.ofSeconds(10));

		assertThatCode(() -> new LeaseConfigurationValidator(properties).afterPropertiesSet())
				.doesNotThrowAnyException();
	}
}
