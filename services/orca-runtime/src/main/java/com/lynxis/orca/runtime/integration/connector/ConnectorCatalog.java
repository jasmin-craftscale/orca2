package com.lynxis.orca.runtime.integration.connector;

import java.util.Optional;

/**
 * Resolves a CONNECTOR node's authored configuration at call time — the endpoint
 * template, the field mappings, the per-status dataset extraction.
 *
 * <p><b>A port, and unbound on purpose.</b> The system this was translated from
 * reads this configuration out of its designer's own tables at call time. This
 * repository has no such tables yet: where designer-authored connector
 * configuration lives is a storage decision that belongs to the designer/publish
 * work, and inventing a schema for it here would settle a question that is still
 * open. Until the adapter lands, a connector call fails by name — never by
 * quietly calling an endpoint that was configured nowhere.
 *
 * <p>Distinct from {@code connector_config} (V102), deliberately: that table is
 * the hand-configured way out for the gate process (a name, a base URL, a
 * deadline). What this port carries is the designer's authored shape — templated
 * endpoints, request-body mappings, response extraction — which is a different
 * thing configured by different people.
 */
public interface ConnectorCatalog {

	/**
	 * @param nodeUuid the CONNECTOR node's uuid
	 * @return empty when no active connector configuration carries that uuid — a
	 *     workflow referring to a connector that has since been deleted, which the
	 *     gateway must report rather than paper over
	 */
	Optional<ConnectorDefinition> definitionFor(String nodeUuid);

	/** The state until the storage decision is made: every lookup is a named failure. */
	ConnectorCatalog UNBOUND = nodeUuid -> {
		throw new UnsupportedOperationException("ConnectorCatalog is not bound yet — "
				+ "where designer-authored connector configuration is stored is an open "
				+ "decision; refusing rather than resolving connector node " + nodeUuid
				+ " from nowhere");
	};
}
