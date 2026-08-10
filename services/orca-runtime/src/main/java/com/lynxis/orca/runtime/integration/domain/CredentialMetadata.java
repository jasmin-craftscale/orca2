package com.lynxis.orca.runtime.integration.domain;

import java.time.Instant;

/** The complete read surface: presence and version, never principal or secret. */
public record CredentialMetadata(
		CredentialMode mode,
		boolean configured,
		long version,
		Instant changedAt,
		String changedBy) {
}
