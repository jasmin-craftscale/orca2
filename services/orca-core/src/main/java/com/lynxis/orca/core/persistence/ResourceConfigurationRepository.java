package com.lynxis.orca.core.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.DeviceTables.ResourceConfiguration;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** {@code resource_configuration} through the seam — site-dimensional. */
@RequiredArgsConstructor
public class ResourceConfigurationRepository {

	private static final String SCOPE = "site_external_id";

	private static final RowMapper<ResourceConfiguration> MAPPER = (rs, row) -> new ResourceConfiguration(
			rs.getLong("resource_configuration_id"),
			rs.getString("site_external_id"),
			rs.getString("scope_type"),
			rs.getString("resource_external_id"),
			rs.getString("config_key"),
			rs.getString("config_value"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<ResourceConfiguration> activeFor(String scopeType, String resourceExternalId) {
		return seam.select(ScopedSelect.from("resource_configuration")
						.columns("resource_configuration_id", "site_external_id", "scope_type",
								"resource_external_id", "config_key", "config_value",
								"retired_at", "created_at")
						.scopedBy(SCOPE)
						.where("scope_type = ? AND resource_external_id = ? AND retired_at IS NULL",
								scopeType, resourceExternalId)
						.orderBy("resource_configuration_id"),
				MAPPER);
	}

	/** Retires the resource's variable set and writes the new one — PUT semantics. */
	public void replaceFor(String scopeType, String resourceExternalId, String siteExternalId,
			Collection<Entry> entries) {
		seam.update(ScopedUpdate.table("resource_configuration")
				.set("retired_at", Utc.now())
				.scopedBy(SCOPE)
				.where("scope_type = ? AND resource_external_id = ? AND retired_at IS NULL",
						scopeType, resourceExternalId));
		for (Entry entry : entries) {
			seam.insert(ScopedInsert.into("resource_configuration")
					.scopedBy(SCOPE)
					.value(SCOPE, siteExternalId)
					.value("scope_type", scopeType)
					.value("resource_external_id", resourceExternalId)
					.value("config_key", entry.key())
					.value("config_value", entry.value()));
		}
	}

	public record Entry(String key, String value) {
	}

	/** The site a scoped resource belongs to, resolved through the published view. */
	public Optional<String> siteOfLaneOrArea(String scopeType, String resourceExternalId) {
		String column = "LANE".equals(scopeType) ? "lane_external_id" : "area_external_id";
		return seam.select(ScopedSelect.from("topology_lane")
						.columns("site_external_id")
						.scopedBy(SCOPE)
						.where(column + " = ?", resourceExternalId),
				(rs, row) -> rs.getString("site_external_id"))
				.stream().findFirst();
	}
}
