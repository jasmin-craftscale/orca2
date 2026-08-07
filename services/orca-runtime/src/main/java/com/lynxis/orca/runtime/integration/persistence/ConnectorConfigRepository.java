package com.lynxis.orca.runtime.integration.persistence;

import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.runtime.integration.domain.ConnectorTables.ConnectorConfig;

import lombok.RequiredArgsConstructor;

/** Connector configuration and its response routing, through the scope seam. */
@RequiredArgsConstructor
public class ConnectorConfigRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	private final ScopeSeam seam;

	public Optional<ConnectorConfig> byName(String connectorName) {
		return seam.select(ScopedSelect.from("connector_config")
								.columns("site_external_id", "connector_name", "base_url", "request_path",
										"deadline_ms", "is_enabled")
								.scopedBy(SCOPE_COLUMN)
								.where("connector_name = ? AND is_enabled = 1", connectorName),
						(rs, row) -> new ConnectorConfig(
								rs.getString("site_external_id"),
								rs.getString("connector_name"),
								rs.getString("base_url"),
								rs.getString("request_path"),
								rs.getInt("deadline_ms"),
								rs.getBoolean("is_enabled")))
				.stream().findFirst();
	}

	/**
	 * The branch discriminator this connector's answer maps to.
	 *
	 * <p>Empty when nothing maps it. The caller turns that into {@code HTTP_<status>}
	 * rather than guessing — an answer nobody wrote a branch for goes to a human,
	 * never to an implicit approval.
	 */
	public Optional<String> outcomeFor(String connectorName, int httpStatus) {
		return seam.select(ScopedSelect.from("connector_route")
								.columns("outcome")
								.scopedBy(SCOPE_COLUMN)
								.where("connector_name = ? AND http_status = ?", connectorName, httpStatus),
						(rs, row) -> rs.getString("outcome"))
				.stream().findFirst();
	}
}
