package com.lynxis.orca.platform.secrets;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/** Refuses missing, malformed, ambiguous, or public production key material. */
public class SecretsConfigurationValidator implements InitializingBean {

	private static final byte[] LOCAL_FIXTURE_KEY =
			Base64.getDecoder().decode(SecretsProperties.LOCAL_FIXTURE_BASE64);

	private final SecretsProperties properties;
	private final Environment environment;
	private SecretBox validated;

	public SecretsConfigurationValidator(SecretsProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		Map<String, byte[]> candidate =
				SecretBox.validateAndDecode(properties.getCurrentKeyId(), properties.getKeys());
		boolean local = List.of(environment.getActiveProfiles()).contains("local");
		if (!local && candidate.values().stream().anyMatch(key -> Arrays.equals(key, LOCAL_FIXTURE_KEY))) {
			throw new SecretConfigurationException(
					"orca.secrets.keys contains the committed local development fixture while the `local` profile is inactive.");
		}
		validated = new SecretBox(properties.getCurrentKeyId(), candidate, new SecureRandom());
	}

	SecretBox secretBox() {
		if (validated == null) {
			throw new SecretConfigurationException("Secret configuration has not been validated.");
		}
		return validated;
	}
}
