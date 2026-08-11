package com.lynxis.orca.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecretsConfigurationValidatorTest {

	@Test
	void missingCurrentIdIsRefusedIndependently() {
		SecretsProperties properties = valid();
		properties.setCurrentKeyId(" ");
		assertThatThrownBy(() -> validate(properties, new MockEnvironment()))
				.isInstanceOf(SecretConfigurationException.class)
				.hasMessage("orca.secrets.current-key-id is missing or blank.");
	}

	@Test
	void emptyKeyMapIsRefusedIndependently() {
		SecretsProperties properties = valid();
		properties.setKeys(Map.of());
		assertInvalid(properties, "keys");
		properties.setKeys(null);
		assertInvalid(properties, "keys");
	}

	@Test
	void currentIdMustNameAConfiguredKey() {
		SecretsProperties properties = valid();
		properties.setCurrentKeyId("v2");
		assertInvalid(properties, "current-key-id");
	}

	@Test
	void malformedBase64IsRefusedWithoutPrintingTheValue() {
		SecretsProperties properties = valid();
		properties.setKeys(Map.of("v1", "sensitive-not-base64***"));
		assertThatThrownBy(() -> validate(properties, new MockEnvironment()))
				.isInstanceOf(SecretConfigurationException.class)
				.hasMessageContaining("v1", "Base64")
				.hasMessageNotContaining("sensitive-not-base64");
	}

	@Test
	void everyKeyMustDecodeToExactlyThirtyTwoBytes() {
		SecretsProperties properties = valid();
		properties.setKeys(Map.of("v1", encoded("short")));
		assertInvalid(properties, "32 bytes");
	}

	@Test
	void blankInvalidAndOverlongKeyIdsAreRefused() {
		for (String invalid : new String[] { "", "bad key", "x".repeat(65) }) {
			SecretsProperties properties = valid();
			properties.setCurrentKeyId("v1");
			properties.setKeys(Map.of(invalid, key()));
			assertInvalid(properties, "invalid key id");
		}
	}

	@Test
	void thePublicFixtureIsRefusedOutsideLocalRegardlessOfBase64Padding() {
		SecretsProperties properties = localFixture();
		assertThatThrownBy(() -> validate(properties, new MockEnvironment()))
				.isInstanceOf(SecretConfigurationException.class)
				.hasMessageContaining("local development fixture")
				.hasMessageNotContaining(SecretsProperties.LOCAL_FIXTURE_BASE64);

		properties.setKeys(Map.of("local-dev-v1",
				SecretsProperties.LOCAL_FIXTURE_BASE64.replace("=", "")));
		assertThatThrownBy(() -> validate(properties, new MockEnvironment()))
				.isInstanceOf(SecretConfigurationException.class)
				.hasMessageContaining("local development fixture");
	}

	@Test
	void thePublicFixtureIsAcceptedOnlyUnderLocal() {
		SecretsProperties properties = localFixture();
		assertThatCode(() -> validate(properties, new MockEnvironment().withProperty("spring.profiles.active", "local")))
				.doesNotThrowAnyException();
	}

	@Test
	void directConstructionCannotBypassKeyValidation() {
		assertThatThrownBy(() -> new SecretBox("v1", Map.of("v1", encoded("short"))))
				.isInstanceOf(SecretConfigurationException.class);
	}

	@Test
	void validatedConfigurationIsSnapshottedIntoTheImmutableSecretBox() throws Exception {
		SecretsProperties properties = valid();
		SecretsConfigurationValidator validator = validator(properties, new MockEnvironment());
		validator.afterPropertiesSet();
		properties.setCurrentKeyId("changed-after-validation");
		properties.setKeys(Map.of());
		assertThat(validator.secretBox().currentKeyId()).isEqualTo("v1");
	}

	private static void assertInvalid(SecretsProperties properties, String messagePart) {
		assertThatThrownBy(() -> validate(properties, new MockEnvironment()))
				.isInstanceOf(SecretConfigurationException.class)
				.hasMessageContaining(messagePart)
				.hasMessageNotContaining(key());
	}

	private static void validate(SecretsProperties properties, MockEnvironment environment) throws Exception {
		validator(properties, environment).afterPropertiesSet();
	}

	private static SecretsConfigurationValidator validator(
			SecretsProperties properties, MockEnvironment environment) {
		return new SecretsConfigurationValidator(properties, environment);
	}

	private static SecretsProperties valid() {
		SecretsProperties properties = new SecretsProperties();
		properties.setCurrentKeyId("v1");
		properties.setKeys(Map.of("v1", key()));
		return properties;
	}

	private static SecretsProperties localFixture() {
		SecretsProperties properties = new SecretsProperties();
		properties.setCurrentKeyId("local-dev-v1");
		properties.setKeys(Map.of("local-dev-v1", SecretsProperties.LOCAL_FIXTURE_BASE64));
		return properties;
	}

	private static String key() {
		return encoded("a".repeat(32));
	}

	private static String encoded(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
