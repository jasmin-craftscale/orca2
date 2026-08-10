package com.lynxis.orca.platform.secrets;

/** A sealed value did not authenticate under its declared key and purpose. */
public class SecretOpenException extends RuntimeException {

	public SecretOpenException(String message) {
		super(message);
	}
}
