/**
 * The designer-authored outbound connector, translated from the Go executor with
 * its behaviours pinned by tests — field-mapping resolution (including the
 * asymmetries the estate depends on), position-aware URL escaping, per-status
 * dataset extraction. The catalog that stores the authored configuration is a
 * deliberately unbound port, and {@code NoAuthCredentials} is the wired default:
 * opening real customer credentials is the job of the platform's secrets
 * primitive ({@code platform/secrets}) when a connector's auth path is wired,
 * not of bespoke cipher code here.
 */
package com.lynxis.orca.runtime.integration.connector;
