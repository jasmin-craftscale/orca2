package com.lynxis.orca.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * The installation's identity in the scope mechanism — one property, bound
 * once, injected as a type rather than thirteen copies of a {@code @Value}
 * expression (review finding, Phase 2 addendum).
 *
 * @param siteExternalId the installation's own site, from configuration and
 *                       never from a request (phase-1 decision 7). The
 *                       committed default is the demo fixture;
 *                       {@link InstallationSiteValidator} refuses to start a
 *                       non-{@code local} service with it, the same way the
 *                       internal credential's fixture is refused (ADR-011's
 *                       pattern) — a production install that forgot
 *                       {@code ORCA_SITE_EXTERNAL_ID} must fail loudly, not
 *                       boot silently scoped to a site that does not exist
 */
@Validated
@ConfigurationProperties(prefix = "orca.installation")
public record InstallationProperties(@NotBlank String siteExternalId) {

	/** The committed local fixture, recognised by name — see {@link InstallationSiteValidator}. */
	public static final String LOCAL_FIXTURE_SITE = "SITE-DEMO";
}
