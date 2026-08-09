package com.lynxis.orca.core;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start a service whose installation site is the committed demo
 * fixture unless the {@code local} profile says this genuinely is a
 * development machine.
 *
 * <p>The same mechanism, for the same reason, as
 * {@code InternalCredentialValidator}: a deployment that forgot
 * {@code ORCA_SITE_EXTERNAL_ID} would otherwise boot green and run silently
 * scoped to a site that does not exist — every read empty, every write
 * refused, and nothing to say why. Refusing at startup is the honest failure.
 */
public class InstallationSiteValidator {

	public InstallationSiteValidator(InstallationProperties properties, Environment environment) {
		// Belt and braces beside the record's @NotBlank: a blank site would pass
		// the fixture check below and boot into exactly the silent mis-scope
		// this class exists to refuse.
		if (properties.siteExternalId() == null || properties.siteExternalId().isBlank()) {
			throw new IllegalStateException(
					"orca.installation.site-external-id is blank. Set ORCA_SITE_EXTERNAL_ID to this "
							+ "installation's site.");
		}
		boolean local = environment.acceptsProfiles(Profiles.of("local"));
		if (!local && InstallationProperties.LOCAL_FIXTURE_SITE.equals(properties.siteExternalId())) {
			throw new IllegalStateException(
					"orca.installation.site-external-id is the committed demo fixture '"
							+ InstallationProperties.LOCAL_FIXTURE_SITE + "' and the 'local' profile is "
							+ "not active. Set ORCA_SITE_EXTERNAL_ID to this installation's site — a "
							+ "service scoped to a site that does not exist reads nothing and writes "
							+ "nothing, silently.");
		}
	}
}
