package com.lynxis.orca.runtime.execution.delegate.spi;

import java.util.Map;

/**
 * A connector's answer: the status code the compiled response gateway routes on, and the
 * dataset entries the adapter extracted from the response body per the connector's
 * {@code data_set_mapping} — the values downstream conditions branch on.
 */
public record ConnectorResult(int status, Map<String, Object> dataset) {

    public ConnectorResult {
        dataset = dataset == null ? Map.of() : Map.copyOf(dataset);
    }
}
