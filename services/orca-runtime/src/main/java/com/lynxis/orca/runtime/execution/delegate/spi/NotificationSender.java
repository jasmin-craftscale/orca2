package com.lynxis.orca.runtime.execution.delegate.spi;

/** The notify seam — the notify module provides the adapter. */
public interface NotificationSender {

    void send(String idempotencyKey, String siteExternalId, String nodeUuid, String name);
}
