package com.lynxis.orca.runtime.integration.domain;

/** Explicit write intent; omission is never guessed from null or an empty string. */
public sealed interface CredentialMutation permits CredentialMutation.SetBasic, CredentialMutation.Clear {

	record SetBasic(String principal, PasswordChange passwordChange) implements CredentialMutation {
	}

	record Clear() implements CredentialMutation {
	}

	sealed interface PasswordChange permits Replace, Preserve {
	}

	record Replace(String plaintext) implements PasswordChange {
	}

	record Preserve() implements PasswordChange {
	}
}
