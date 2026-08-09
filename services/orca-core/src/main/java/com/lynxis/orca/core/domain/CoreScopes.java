package com.lynxis.orca.core.domain;

import java.util.Set;

import com.lynxis.orca.platform.scope.Scope;

/**
 * How core's request boundary builds its scope — in one place, so the two
 * dimensions cannot drift apart between controllers.
 *
 * <p>The installation's site comes from configuration
 * ({@code orca.installation.site-external-id}), never from the request, because a
 * caller cannot choose its own scope. {@code config_realm} is the second
 * dimension: the explicit way to read installation-wide configuration when the
 * seam deliberately offers no unscoped read.
 *
 * <p>An installation with more than one site belongs to the deferred hosted/cloud
 * design. If that scope opens, this is the one method that must widen.
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
