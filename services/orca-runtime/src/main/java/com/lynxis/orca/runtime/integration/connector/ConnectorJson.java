package com.lynxis.orca.runtime.integration.connector;

import tools.jackson.databind.ObjectMapper;

/** Shared constants and the one mapper the connector package parses stored JSON with. */
final class ConnectorJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code utils.AttributePrefix} — a field key marking an XML attribute, not an element. */
    static final String ATTRIBUTE_PREFIX = "-";

    /** {@code utils.TextNodeKey} — where an element's text goes when it also has attributes. */
    static final String TEXT_NODE_KEY = "#text";

    private ConnectorJson() {
    }
}
