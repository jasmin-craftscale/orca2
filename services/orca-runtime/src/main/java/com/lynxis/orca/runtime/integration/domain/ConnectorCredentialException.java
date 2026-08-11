package com.lynxis.orca.runtime.integration.domain;

/** Typed, redacted refusals for a future authorised HTTP adapter. */
public class ConnectorCredentialException extends RuntimeException {

	private ConnectorCredentialException(String message) {
		super(message);
	}

	public static final class NotFound extends ConnectorCredentialException {
		NotFound() {
			super("Connector does not exist at this installation.");
		}
	}

	public static final class InvalidState extends ConnectorCredentialException {
		InvalidState(String category) {
			super("Connector credential mutation was refused: " + category + ".");
		}
	}

	public static final class StaleVersion extends ConnectorCredentialException {
		private final long expected;
		private final long current;

		StaleVersion(long expected, long current) {
			super("Connector credential version conflict.");
			this.expected = expected;
			this.current = current;
		}

		public long expected() {
			return expected;
		}

		public long current() {
			return current;
		}
	}
}
