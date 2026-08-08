package com.lynxis.orca.core.persistence;

import java.util.List;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.DeviceTables.DeviceIoPortName;
import com.lynxis.orca.core.domain.DeviceTables.DeviceType;
import com.lynxis.orca.core.domain.DeviceTables.IoDeviceKind;
import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * Read-only access to the three seeded device catalogs (V106). Writes happen
 * only in migrations — the catalogs are product-owned reference data (rule 7).
 */
@RequiredArgsConstructor
public class DeviceCatalogRepository {

	private static final String REALM = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final RowMapper<DeviceType> TYPE_MAPPER = (rs, row) -> new DeviceType(
			rs.getLong("device_type_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("code"),
			rs.getString("name"),
			rs.getString("form_schema"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<DeviceIoPortName> PORT_NAME_MAPPER = (rs, row) -> new DeviceIoPortName(
			rs.getLong("port_name_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("port_type"),
			rs.getString("code"),
			rs.getString("name"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<IoDeviceKind> KIND_MAPPER = (rs, row) -> new IoDeviceKind(
			rs.getLong("io_device_kind_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("port_type"),
			rs.getString("code"),
			rs.getString("name"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<DeviceType> deviceTypes() {
		return seam.select(ScopedSelect.from("device_type")
						.columns("device_type_id", "external_id", "config_realm", "code", "name",
								"form_schema", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("device_type_id"),
				TYPE_MAPPER);
	}

	public List<DeviceIoPortName> ioPortNames() {
		return seam.select(ScopedSelect.from("device_io_port_name")
						.columns("port_name_id", "external_id", "config_realm", "port_type", "code",
								"name", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("port_name_id"),
				PORT_NAME_MAPPER);
	}

	public List<IoDeviceKind> ioDeviceKinds() {
		return seam.select(ScopedSelect.from("io_device_kind")
						.columns("io_device_kind_id", "external_id", "config_realm", "port_type",
								"code", "name", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("io_device_kind_id"),
				KIND_MAPPER);
	}
}
