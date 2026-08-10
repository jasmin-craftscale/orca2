package com.lynxis.orca.platform.secrets;

/** A key-ring configuration is incomplete or cannot safely be used. */
public class SecretConfigurationException extends IllegalStateException {

	public SecretConfigurationException(String message) {
		super(message);
	}
}
