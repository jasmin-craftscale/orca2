package com.lynxis.orca.runtime.integration.connector;

import java.util.Map;

/**
 * The one place a connector credential is allowed to become plaintext.
 *
 * <p>This is a port rather than a method for a specific reason: {@code connector_config.auth}
 * holds real customer credentials for real customer systems, and the code that opens it needs
 * to be a small, isolated thing a person can read in one sitting and sign off. Everything
 * around it — resolving the connector, building the body, routing on the status — works
 * against this interface and never touches the ciphertext, so reviewing the credential path
 * means reviewing one file, not auditing a gateway.
 *
 * <p>Implementations must never log, echo, or attach the decrypted material to anything that
 * outlives the call.
 */
public interface ConnectorCredentials {

    /**
     * @param authCipher the encrypted {@code connector_config.auth} exactly as stored
     * @return the headers to add to the outbound request; empty means "send it unauthenticated"
     */
    Map<String, String> headersFor(String authCipher);
}
