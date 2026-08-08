package com.lynxis.orca.core.domain;

import java.util.Set;

import com.lynxis.orca.platform.scope.Scope;

/**
 * How core's request boundary builds its scope — in one place, so the two
 * dimensions cannot drift apart between controllers.
 *
 * <p>Core follows the fielded pattern (phase-1 decision 7): the installation's
 * site comes from configuration ({@code orca.installation.site-external-id}),
 * never from the request. To that this phase adds the second dimension,
 * {@code config_realm} — the declared way to read installation-wide
 * configuration, since the seam deliberately has no unscoped read.
 *
 * <p>An installation with more than one site is the hosted tier's shape and is
 * deferred with cloud scope (register NEW-1b); when it arrives, this is the one
 * method that widens.
 */
public final class CoreScopes {

	private CoreScopes() {
	}

	public static Scope installation(String siteExternalId) {
		return Scope.builder()
				.permit("site_external_id", Set.of(siteExternalId))
				.permit(IdentityTables.CONFIG_REALM_DIMENSION, Set.of(IdentityTables.INSTALLATION_REALM))
				.build();
	}
}
