package com.lynxis.orca.runtime.execution.delegate.spi;

/**
 * The integration seam every CONNECTOR node calls through. The integration module
 * provides the adapter — per-connector breaker and bulkhead live THERE (one slow
 * customer system must not exhaust the engine's job threads), the delegate stays thin.
 */
public interface ConnectorGateway {

    ConnectorResult call(ConnectorRequest request);
}
