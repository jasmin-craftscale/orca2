package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The startup guard on the installation site — no container, no context: the
 * validator is a constructor that throws, and these are its three cases.
 * It follows {@code InternalCredentialValidator}'s fail-fast pattern for the same
 * reason: a committed local fixture must not reach a deployment by inertia.
 */
class InstallationSiteValidatorTest {

	@Test
	@DisplayName("the demo-fixture site outside the local profile refuses to start")
	void theFixtureIsRefusedOutsideLocal() {
		assertThatThrownBy(() -> new InstallationSiteValidator(
				new InstallationProperties("SITE-DEMO"), new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ORCA_SITE_EXTERNAL_ID");
	}

	@Test
	@DisplayName("the same fixture under the local profile is a development machine, and starts")
	void theFixtureIsFineUnderLocal() {
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");
		assertThatCode(() -> new InstallationSiteValidator(
				new InstallationProperties("SITE-DEMO"), local))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("a blank site id is refused everywhere — silence is the failure this guard exists for")
	void aBlankSiteIsRefused() {
		assertThatThrownBy(() -> new InstallationSiteValidator(
				new InstallationProperties("  "), new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("blank");
	}

	@Test
	@DisplayName("a real site id starts anywhere")
	void aRealSiteStartsAnywhere() {
		assertThatCode(() -> new InstallationSiteValidator(
				new InstallationProperties("SITE-HAMBURG-01"), new MockEnvironment()))
				.doesNotThrowAnyException();
	}
}
