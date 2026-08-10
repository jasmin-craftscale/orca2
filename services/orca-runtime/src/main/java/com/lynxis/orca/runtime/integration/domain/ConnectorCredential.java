package com.lynxis.orca.runtime.integration.domain;

import java.time.Instant;
import java.util.List;

import com.lynxis.orca.platform.secrets.SealedSecret;
import com.lynxis.orca.platform.secrets.SecretPurpose;

/** Runtime-owned current state; sealed fields never cross an API boundary. */
public record ConnectorCredential(
		String siteExternalId,
		String connectorName,
		CredentialMode mode,
		String principal,
		SealedSecret sealedSecret,
		long version,
		Instant changedAt,
		String changedBy) {

	public static SecretPurpose purpose(String siteExternalId, String connectorName) {
		return new SecretPurpose("runtime", List.of(siteExternalId, connectorName),
				"connector-basic-password");
	}
}
