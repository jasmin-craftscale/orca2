package com.lynxis.orca.runtime.integration.domain;

/** Explicit write intent; omission is never guessed from null or an empty string. */
public sealed interface CredentialMutation permits CredentialMutation.SetBasic, CredentialMutation.Clear {

	record SetBasic(String principal, PasswordChange passwordChange) implements CredentialMutation {

		@Override
		public String toString() {
			String change = passwordChange == null ? "null" : passwordChange.getClass().getSimpleName();
			return "SetBasic[principal=<redacted>, passwordChange=" + change + "]";
		}
	}

	record Clear() implements CredentialMutation {
	}

	sealed interface PasswordChange permits Replace, Preserve {
	}

	record Replace(String plaintext) implements PasswordChange {

		@Override
		public String toString() {
			return "Replace[plaintext=<redacted>]";
		}
	}

	record Preserve() implements PasswordChange {
	}
}
