package com.lynxis.orca.platform.web.internal;

import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import lombok.RequiredArgsConstructor;

/**
 * Refuses to start a service whose inter-service credential is missing, trivial,
 * or the committed local fixture outside local development.
 *
 * <p>The last of those is the one worth having. Every other check here catches a
 * mistake somebody would notice; shipping {@code .env.example}'s value to a
 * customer site is a mistake that works perfectly, indefinitely, until the day
 * somebody reads the file.
 */
@RequiredArgsConstructor
public class InternalCredentialValidator implements InitializingBean {

	/**
	 * Long enough that guessing is not the attack. Not a policy, a floor: a
	 * credential shorter than this was typed by a person rather than generated.
	 */
	private static final int MINIMUM_LENGTH = 32;

	private final InternalCallProperties properties;
	private final Environment environment;

	@Override
	public void afterPropertiesSet() {
		String credential = properties.getSharedCredential();

		if (credential == null || credential.isBlank()) {
			throw new IllegalStateException(
					"orca.internal.shared-credential is not set, and there is no default. "
							+ "Service-to-service calls authenticate with a per-installation shared "
							+ "credential (ADR-011); a service without one cannot be called by its peers "
							+ "and cannot call them.");
		}

		boolean local = List.of(environment.getActiveProfiles()).contains("local");

		if (InternalCallProperties.LOCAL_FIXTURE.equals(credential) && !local) {
			throw new IllegalStateException(
					"orca.internal.shared-credential is the committed local development fixture, and "
							+ "the `local` profile is not active. That value is in .env.example, is "
							+ "identical on every developer's machine, and is public. Set a real "
							+ "per-installation credential, or activate the `local` profile if this "
							+ "genuinely is a laptop.");
		}

		if (!local && credential.length() < MINIMUM_LENGTH) {
			throw new IllegalStateException(
					"orca.internal.shared-credential is " + credential.length() + " characters. "
							+ "At least " + MINIMUM_LENGTH + " are required outside local development: "
							+ "a shorter one was typed by a person rather than generated.");
		}
	}
}
