package com.lynxis.orca.platform.web.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The startup guard on the inter-service credential.
 *
 * <p>The case that matters is the third one: the committed local fixture reaching
 * a deployment. Every other misconfiguration fails loudly somewhere; that one
 * works perfectly until somebody reads a file.
 */
class InternalCredentialValidatorTest {

	@Test
	@DisplayName("no credential refuses startup")
	void missingCredentialRefusesStartup() {
		assertThatThrownBy(() -> validate(null, "prod"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("orca.internal.shared-credential is not set")
				.hasMessageContaining("no default");
	}

	@Test
	@DisplayName("the committed local fixture refuses startup outside the local profile")
	void theLocalFixtureCannotShipToASite() {
		assertThatThrownBy(() -> validate(InternalCallProperties.LOCAL_FIXTURE, "prod"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("committed local development fixture")
				.hasMessageContaining("is public");

		// And with NO profile at all — a site that forgot to set one must not slip
		// through on the default.
		assertThatThrownBy(() -> validate(InternalCallProperties.LOCAL_FIXTURE))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("committed local development fixture");
	}

	@Test
	@DisplayName("the fixture is accepted under the local profile — a laptop is not an installation")
	void theFixtureIsFineLocally() {
		assertThatCode(() -> validate(InternalCallProperties.LOCAL_FIXTURE, "local"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("a short credential refuses startup outside local — it was typed, not generated")
	void aShortCredentialRefusesStartup() {
		assertThatThrownBy(() -> validate("hunter2", "prod"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32");
	}

	@Test
	@DisplayName("a generated credential is accepted")
	void aRealCredentialIsAccepted() {
		assertThatCode(() -> validate("k3H9mQ2vX8pL5wN7cR4tY6uB1sD0fG9jA2eZ", "prod"))
				.doesNotThrowAnyException();
	}

	private static void validate(String credential, String... profiles) {
		InternalCallProperties properties = new InternalCallProperties();
		properties.setSharedCredential(credential);
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles);
		new InternalCredentialValidator(properties, environment).afterPropertiesSet();
	}
}
