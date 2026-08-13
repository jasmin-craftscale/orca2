package com.lynxis.orca.runtime.execution.engine;

import java.util.Objects;

/**
 * The tenant an instance runs for. Carries {@code site_uuid} — the real tenancy binding
 * Note the trap found by the original spike: Flowable's own {@code tenantId} on the instance builder
 * <em>selects the definition</em> rather than tagging the instance; the adapter must treat
 * this value as the D7 security context, not fake tenancy with lanes.
 */
public record TenantRef(String siteUuid) {

    public TenantRef {
        Objects.requireNonNull(siteUuid, "siteUuid");
        if (siteUuid.isBlank()) {
            throw new IllegalArgumentException("siteUuid must not be blank");
        }
    }
}
