package com.lynxis.orca.runtime.integration.connector;

import java.util.List;

/**
 * Everything one CONNECTOR node needs in order to make its call, read at call time.
 *
 * <p><b>Why this is not compiled into the BPMN.</b> The publish payload the compiler consumes
 * carries only the response status codes — which is right, because status codes are workflow
 * shape and belong in the process definition. An endpoint, a payload template or a dataset
 * mapping is not shape: it is configuration that operations changes without touching a
 * workflow. Compiling it in would mean redeploying every definition that touches a connector
 * to change one URL. So the definition routes, and this carries the integration detail.
 *
 * @param authCipher the still-encrypted {@code connector_config.auth}. It is carried as
 *     ciphertext deliberately: only {@link ConnectorCredentials} may open it, so there is
 *     exactly one place in the runtime where a credential becomes plaintext.
 */
record ConnectorDefinition(
        String name,
        String siteExternalId,
        String endpointTemplate,
        String method,
        String contentType,
        String protocolType,
        String soapAction,
        String certificateName,
        String direction,
        String authCipher,
        String fieldMappingsJson,
        String urlParameterJson,
        List<ResponseMapping> responses) {

    /**
     * One configured response: the status code the compiled gateway routes on, and the
     * dataset entries to extract when the call comes back with it.
     *
     * @param statusCode {@code 0} is ORCA's "anything not matched above" row (stored as 000)
     * @param dataSetMappingJson {@code connector_response_node.data_set_field}
     */
    record ResponseMapping(int statusCode, String dataSetMappingJson) {
    }

    boolean isGet() {
        return "GET".equalsIgnoreCase(method);
    }

    boolean isXml() {
        return "xml".equalsIgnoreCase(contentType);
    }

    /**
     * The mapping for a status, falling back to the {@code 000} row — the same precedence the
     * Go executor applies: an exact match wins, and the catch-all answers everything else.
     * Absent entirely means the call succeeded but extracts nothing, which is legal.
     */
    ResponseMapping mappingFor(int status) {
        ResponseMapping fallback = null;
        for (ResponseMapping r : responses) {
            if (r.statusCode() == status) {
                return r;
            }
            if (r.statusCode() == 0) {
                fallback = r;
            }
        }
        return fallback;
    }
}
