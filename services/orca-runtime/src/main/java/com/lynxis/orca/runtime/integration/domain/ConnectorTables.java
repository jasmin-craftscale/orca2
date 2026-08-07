package com.lynxis.orca.runtime.integration.domain;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The tables {@code V102__connectors.sql} creates, declared where the build check
 * can read them.
 *
 * <p>Both are {@link Growth#BOUNDED} and therefore carry no retention class: a row
 * appears when somebody configures a connector, not when a truck arrives. That is
 * the distinction the growth question is asking about.
 */
public final class ConnectorTables {

	private ConnectorTables() {
	}

	/**
	 * One configured way out to a customer system.
	 *
	 * @param requestPath  appended to {@code baseUrl}. Separate so that the two are
	 *                     configured by different people at different times — a site
	 *                     moves a host, a vendor moves a route
	 * @param deadlineMillis §B8's deadline, per connector. A terminal operating
	 *                     system that answers in four seconds and a weighbridge that
	 *                     answers in two hundred milliseconds cannot share one
	 */
	@PersistentTable(name = "connector_config", growth = Growth.BOUNDED)
	public record ConnectorConfig(
			String siteExternalId,
			String connectorName,
			String baseUrl,
			String requestPath,
			int deadlineMillis,
			boolean enabled) {
	}

	/**
	 * One HTTP status mapped to the branch discriminator a process routes on.
	 *
	 * <p>A status with no row becomes {@code HTTP_<status>}, which no compiled
	 * process has a branch for — so it takes the default flow to a human. Intended,
	 * not a fallback: an answer nobody wrote a branch for must never become an
	 * implicit approval.
	 */
	@PersistentTable(name = "connector_route", growth = Growth.BOUNDED)
	public record ConnectorRoute(
			String siteExternalId,
			String connectorName,
			int httpStatus,
			String outcome) {
	}
}
