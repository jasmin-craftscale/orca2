package com.lynxis.orca.runtime.integration.connector;

import java.util.Map;

/**
 * Sends every connector call unauthenticated — the wired default until a person has
 * reviewed and signed off the decrypting implementation. That review is the product
 * owner's call, not an implementer's.
 *
 * <p><b>This is not a stub that pretends to work.</b> It ignores the ciphertext entirely and
 * says so, which means a connector whose target really does require credentials will fail
 * against that target with that target's own 401. That is the honest failure: visible,
 * attributable, and impossible to mistake for success. The alternative — decrypting by
 * default and discovering the key handling was wrong in front of a customer system — is the
 * failure this deliberately trades away.
 */
public final class NoAuthCredentials implements ConnectorCredentials {

    @Override
    public Map<String, String> headersFor(String authCipher) {
        return Map.of();
    }
}
