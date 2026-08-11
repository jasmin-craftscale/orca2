package com.lynxis.orca.platform.secrets;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Wires strict configuration validation before the immutable secret box. */
@AutoConfiguration
@EnableConfigurationProperties(SecretsProperties.class)
public class SecretsAutoConfiguration {

	@Bean
	public SecretsConfigurationValidator secretsConfigurationValidator(
			SecretsProperties properties, Environment environment) {
		return new SecretsConfigurationValidator(properties, environment);
	}

	@Bean
	public SecretBox secretBox(SecretsConfigurationValidator validator) {
		return validator.secretBox();
	}
}
