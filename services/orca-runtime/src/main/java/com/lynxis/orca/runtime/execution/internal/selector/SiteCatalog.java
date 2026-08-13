package com.lynxis.orca.runtime.execution.internal.selector;

/**
 * The half of a selector's world that is <em>configuration</em> rather than <em>this visit</em>:
 * lanes, devices, site resource configurations, workflow and node aliases.
 *
 * <p>It is a port, and unimplemented on purpose. That data belongs to other schemas —
 * topology to orca-core, connector configuration to the integration module — and the
 * adapter that reads it lands with the connector and designer phases, not inside
 * execution, which owns only its own schema. Naming the port now keeps the provider
 * honest: a selector that needs configuration gets a typed refusal saying which method
 * is unbound, never an empty string that quietly routes a truck down the wrong branch.
 */
public interface SiteCatalog {

	String resourceConfiguration(int resourceId, String resourceType, String configKey);

	String laneProperty(String laneCode, String field);

	String deviceUrlForLane(String laneCode, String deviceAlias, String column);

	int primaryKeyByUuid(String tableName, String idColumnName, String uuidColumnName, String uuidValue);

	WorkflowAlias workflowByAlias(String workflowAliasName, int workflowExecutionId);

	String nodeUuidByAlias(int workflowId, String nodeAliasName);

	record WorkflowAlias(int id, String uuid) {
	}

	/**
	 * The state until the adapter lands: every configuration question is a named failure.
	 * Deliberately not a null-returning stub — this runtime's whole premise is that an
	 * unanswerable question stops a visit instead of silently becoming "".
	 */
	SiteCatalog UNBOUND = new SiteCatalog() {

		@Override
		public String resourceConfiguration(int resourceId, String resourceType, String configKey) {
			throw unbound("resourceConfiguration");
		}

		@Override
		public String laneProperty(String laneCode, String field) {
			throw unbound("laneProperty");
		}

		@Override
		public String deviceUrlForLane(String laneCode, String deviceAlias, String column) {
			throw unbound("deviceUrlForLane");
		}

		@Override
		public int primaryKeyByUuid(String table, String idColumn, String uuidColumn, String uuidValue) {
			throw unbound("primaryKeyByUuid");
		}

		@Override
		public WorkflowAlias workflowByAlias(String workflowAliasName, int workflowExecutionId) {
			throw unbound("workflowByAlias");
		}

		@Override
		public String nodeUuidByAlias(int workflowId, String nodeAliasName) {
			throw unbound("nodeUuidByAlias");
		}

		private UnsupportedOperationException unbound(String method) {
			return new UnsupportedOperationException("SiteCatalog." + method
					+ " is not bound yet — the site-configuration adapter lands with the "
					+ "connector phase; refusing rather than resolving the selector to an "
					+ "empty value");
		}
	};
}
