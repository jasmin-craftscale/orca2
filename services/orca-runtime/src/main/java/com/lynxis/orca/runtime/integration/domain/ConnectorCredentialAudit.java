package com.lynxis.orca.runtime.integration.domain;

import java.time.Instant;

/** One redacted, append-only administration fact. */
public record ConnectorCredentialAudit(
		String connectorName,
		CredentialMode mode,
		long version,
		Action action,
		Instant occurredAt,
		String actor) {

	public enum Action {
		SET,
		REPLACE,
		CLEAR,
		REWRAP
	}
}
