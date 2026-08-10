package com.lynxis.orca.platform.secrets;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/** Refuses missing, malformed, ambiguous, or public production key material. */
public class SecretsConfigurationValidator implements InitializingBean {

	private final SecretsProperties properties;
	private final Environment environment;
	private Map<String, byte[]> validated;

	public SecretsConfigurationValidator(SecretsProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		Map<String, byte[]> candidate =
				SecretBox.validateAndDecode(properties.getCurrentKeyId(), properties.getKeys());
		boolean local = List.of(environment.getActiveProfiles()).contains("local");
		if (!local && properties.getKeys().containsValue(SecretsProperties.LOCAL_FIXTURE_BASE64)) {
			throw new SecretConfigurationException(
					"orca.secrets.keys contains the committed local development fixture while the `local` profile is inactive.");
		}
		validated = candidate;
	}

	SecretBox secretBox() {
		if (validated == null) {
			throw new SecretConfigurationException("Secret configuration has not been validated.");
		}
		return new SecretBox(properties.getCurrentKeyId(), validated, new SecureRandom());
	}
}
