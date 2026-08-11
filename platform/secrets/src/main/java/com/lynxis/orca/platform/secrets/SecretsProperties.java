package com.lynxis.orca.platform.secrets;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Versioned AES keys supplied by deployment configuration, never SQL Server. */
@Getter
@Setter
@ConfigurationProperties(prefix = "orca.secrets")
public class SecretsProperties {

	/** Public development-only value, recognised so it cannot ship by inertia. */
	public static final String LOCAL_FIXTURE_BASE64 =
			"b3JjYS1sb2NhbC1zZWNyZXQta2V5LWZpeHR1cmUtMzI=";

	private String currentKeyId;
	private Map<String, String> keys;
}
