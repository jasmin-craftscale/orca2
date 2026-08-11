/**
 * The designer-authored outbound connector, translated from the Go executor with
 * its behaviours pinned by tests — field-mapping resolution (including the
 * asymmetries the estate depends on), position-aware URL escaping, per-status
 * dataset extraction. The catalog that stores the authored configuration is a
 * deliberately unbound port, and the credential-decrypting implementation exists
 * but stays unwired until a person signs it off; {@code NoAuthCredentials} is
 * the wired default.
 */
package com.lynxis.orca.runtime.integration.connector;
