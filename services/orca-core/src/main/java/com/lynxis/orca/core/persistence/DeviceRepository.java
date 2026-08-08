package com.lynxis.orca.core.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.DeviceTables.Device;
import com.lynxis.orca.core.domain.DeviceTables.DeviceIoAssignment;
import com.lynxis.orca.core.domain.DeviceTables.PtzPreset;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * {@code device}, its port layout and its PTZ presets through the seam — all
 * site-dimensional since V106.
 *
 * <p>Lane identity resolves through {@code topology_lane} — core reading its
 * own published view, which carries the scope column the base tables
 * deliberately do not.
 */
@RequiredArgsConstructor
public class DeviceRepository {

	private static final String SCOPE = "site_external_id";

	private static final String[] DEVICE_COLUMNS = {
			"device_id", "external_id", "lane_id", "site_external_id", "device_type_id", "name",
			"address", "device_mode", "device_alias", "device_model", "firmware_version",
			"description", "resolution", "ip_address", "stream_type", "port", "protocol",
			"manufacturer", "device_url", "device_portal_url", "assembly_name", "class_name",
			"data_capture_mode", "wait_time", "retired_at", "created_at" };

	private static final RowMapper<Device> DEVICE_MAPPER = (rs, row) -> new Device(
			rs.getLong("device_id"),
			rs.getString("external_id"),
			rs.getLong("lane_id"),
			rs.getString("site_external_id"),
			rs.getLong("device_type_id"),
			rs.getString("name"),
			rs.getString("address"),
			rs.getString("device_mode"),
			rs.getString("device_alias"),
			rs.getString("device_model"),
			rs.getString("firmware_version"),
			rs.getString("description"),
			rs.getString("resolution"),
			rs.getString("ip_address"),
			rs.getString("stream_type"),
			(Integer) rs.getObject("port"),
			rs.getString("protocol"),
			rs.getString("manufacturer"),
			rs.getString("device_url"),
			rs.getString("device_portal_url"),
			rs.getString("assembly_name"),
			rs.getString("class_name"),
			rs.getString("data_capture_mode"),
			(Integer) rs.getObject("wait_time"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<DeviceIoAssignment> ASSIGNMENT_MAPPER = (rs, row) -> new DeviceIoAssignment(
			rs.getLong("device_io_assignment_id"),
			rs.getLong("device_id"),
			rs.getString("site_external_id"),
			rs.getString("port_type"),
			rs.getInt("io_port"),
			(Long) rs.getObject("port_name_id"),
			rs.getString("port_alias_name"),
			rs.getBoolean("is_reverse_state"),
			rs.getBoolean("is_initial_high"),
			rs.getBoolean("is_data_capture"),
			rs.getBoolean("is_gos_audio"),
			rs.getBoolean("produce_true_message"),
			rs.getBoolean("produce_false_message"),
			(Integer) rs.getObject("wait_time"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<PtzPreset> PRESET_MAPPER = (rs, row) -> new PtzPreset(
			rs.getLong("ptz_preset_id"),
			rs.getLong("device_id"),
			rs.getString("site_external_id"),
			rs.getString("name"),
			rs.getBigDecimal("pan"),
			rs.getBigDecimal("tilt"),
			rs.getBigDecimal("zoom"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	// --- lanes, via the published view --------------------------------------

	/** The lane's internal key, or empty when the site does not publish it. */
	public Optional<LaneRef> laneByExternalId(String laneExternalId) {
		return seam.select(ScopedSelect.from("topology_lane")
						.columns("lane_id", "lane_external_id")
						.scopedBy(SCOPE)
						.where("lane_external_id = ?", laneExternalId),
				(rs, row) -> new LaneRef(rs.getLong("lane_id"), rs.getString("lane_external_id")))
				.stream().findFirst();
	}

	/** Every published lane, for mapping internal keys back to external ids. */
	public List<LaneRef> publishedLanes() {
		return seam.select(ScopedSelect.from("topology_lane")
						.columns("lane_id", "lane_external_id")
						.scopedBy(SCOPE)
						.orderBy("lane_id"),
				(rs, row) -> new LaneRef(rs.getLong("lane_id"), rs.getString("lane_external_id")));
	}

	public record LaneRef(long laneId, String laneExternalId) {
	}

	// --- devices ------------------------------------------------------------

	public List<Device> byLane(long laneId) {
		return seam.select(ScopedSelect.from("device")
						.columns(DEVICE_COLUMNS)
						.scopedBy(SCOPE)
						.where("lane_id = ?", laneId)
						.orderBy("device_id"),
				DEVICE_MAPPER);
	}

	public Optional<Device> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from("device")
						.columns(DEVICE_COLUMNS)
						.scopedBy(SCOPE)
						.where("external_id = ?", externalId),
				DEVICE_MAPPER)
				.stream().findFirst();
	}

	public void insert(Device draft) {
		seam.insert(ScopedInsert.into("device")
				.scopedBy(SCOPE)
				.value("external_id", draft.externalId())
				.value("lane_id", draft.laneId())
				.value(SCOPE, draft.siteExternalId())
				.value("device_type_id", draft.deviceTypeId())
				.value("name", draft.name())
				.value("address", draft.address())
				.value("device_mode", draft.deviceMode())
				.value("device_alias", draft.deviceAlias())
				.value("device_model", draft.deviceModel())
				.value("firmware_version", draft.firmwareVersion())
				.value("description", draft.description())
				.value("resolution", draft.resolution())
				.value("ip_address", draft.ipAddress())
				.value("stream_type", draft.streamType())
				.value("port", draft.port())
				.value("protocol", draft.protocol() == null ? "http" : draft.protocol())
				.value("manufacturer", draft.manufacturer())
				.value("device_url", draft.deviceUrl())
				.value("device_portal_url", draft.devicePortalUrl())
				.value("assembly_name", draft.assemblyName())
				.value("class_name", draft.className())
				.value("data_capture_mode", draft.dataCaptureMode())
				.value("wait_time", draft.waitTime()));
	}

	/** Applies the non-null fields of {@code patch}; {@code retired} tri-state as elsewhere. */
	public int update(String externalId, DevicePatch patch) {
		ScopedUpdate update = ScopedUpdate.table("device")
				.scopedBy(SCOPE)
				.where("external_id = ?", externalId);
		boolean touched = false;
		if (patch.deviceTypeId() != null) {
			update.set("device_type_id", patch.deviceTypeId());
			touched = true;
		}
		touched |= set(update, "name", patch.name());
		touched |= set(update, "address", patch.address());
		touched |= set(update, "device_mode", patch.deviceMode());
		touched |= set(update, "device_alias", patch.deviceAlias());
		touched |= set(update, "device_model", patch.deviceModel());
		touched |= set(update, "firmware_version", patch.firmwareVersion());
		touched |= set(update, "description", patch.description());
		touched |= set(update, "resolution", patch.resolution());
		touched |= set(update, "ip_address", patch.ipAddress());
		touched |= set(update, "stream_type", patch.streamType());
		touched |= set(update, "protocol", patch.protocol());
		touched |= set(update, "manufacturer", patch.manufacturer());
		touched |= set(update, "device_url", patch.deviceUrl());
		touched |= set(update, "device_portal_url", patch.devicePortalUrl());
		touched |= set(update, "assembly_name", patch.assemblyName());
		touched |= set(update, "class_name", patch.className());
		touched |= set(update, "data_capture_mode", patch.dataCaptureMode());
		if (patch.port() != null) {
			update.set("port", patch.port());
			touched = true;
		}
		if (patch.waitTime() != null) {
			update.set("wait_time", patch.waitTime());
			touched = true;
		}
		if (patch.retired() != null) {
			update.set("retired_at", patch.retired() ? Utc.now() : null);
			touched = true;
		}
		return touched ? seam.update(update) : 0;
	}

	private static boolean set(ScopedUpdate update, String column, String value) {
		if (value == null) {
			return false;
		}
		update.set(column, value);
		return true;
	}

	public record DevicePatch(
			Long deviceTypeId,
			String name,
			String address,
			String deviceMode,
			String deviceAlias,
			String deviceModel,
			String firmwareVersion,
			String description,
			String resolution,
			String ipAddress,
			String streamType,
			Integer port,
			String protocol,
			String manufacturer,
			String deviceUrl,
			String devicePortalUrl,
			String assemblyName,
			String className,
			String dataCaptureMode,
			Integer waitTime,
			Boolean retired) {
	}

	// --- port layout --------------------------------------------------------

	public List<DeviceIoAssignment> activeAssignments() {
		return seam.select(ScopedSelect.from("device_io_assignment")
						.columns("device_io_assignment_id", "device_id", "site_external_id", "port_type",
								"io_port", "port_name_id", "port_alias_name", "is_reverse_state",
								"is_initial_high", "is_data_capture", "is_gos_audio",
								"produce_true_message", "produce_false_message", "wait_time",
								"retired_at", "created_at")
						.scopedBy(SCOPE)
						.where("retired_at IS NULL")
						.orderBy("device_io_assignment_id"),
				ASSIGNMENT_MAPPER);
	}

	/** Retires the device's layout and writes the new one — PUT semantics. */
	public void replaceAssignments(long deviceId, String siteExternalId,
			Collection<DeviceIoAssignment> assignments) {
		seam.update(ScopedUpdate.table("device_io_assignment")
				.set("retired_at", Utc.now())
				.scopedBy(SCOPE)
				.where("device_id = ? AND retired_at IS NULL", deviceId));
		for (DeviceIoAssignment assignment : assignments) {
			seam.insert(ScopedInsert.into("device_io_assignment")
					.scopedBy(SCOPE)
					.value("device_id", deviceId)
					.value(SCOPE, siteExternalId)
					.value("port_type", assignment.portType())
					.value("io_port", assignment.ioPort())
					.value("port_name_id", assignment.portNameId())
					.value("port_alias_name", assignment.portAliasName())
					.value("is_reverse_state", assignment.isReverseState())
					.value("is_initial_high", assignment.isInitialHigh())
					.value("is_data_capture", assignment.isDataCapture())
					.value("is_gos_audio", assignment.isGosAudio())
					.value("produce_true_message", assignment.produceTrueMessage())
					.value("produce_false_message", assignment.produceFalseMessage())
					.value("wait_time", assignment.waitTime()));
		}
	}

	// --- presets ------------------------------------------------------------

	public List<PtzPreset> activePresets() {
		return seam.select(ScopedSelect.from("ptz_preset")
						.columns("ptz_preset_id", "device_id", "site_external_id", "name", "pan",
								"tilt", "zoom", "retired_at", "created_at")
						.scopedBy(SCOPE)
						.where("retired_at IS NULL")
						.orderBy("ptz_preset_id"),
				PRESET_MAPPER);
	}

	public void replacePresets(long deviceId, String siteExternalId, Collection<PtzPreset> presets) {
		seam.update(ScopedUpdate.table("ptz_preset")
				.set("retired_at", Utc.now())
				.scopedBy(SCOPE)
				.where("device_id = ? AND retired_at IS NULL", deviceId));
		for (PtzPreset preset : presets) {
			seam.insert(ScopedInsert.into("ptz_preset")
					.scopedBy(SCOPE)
					.value("device_id", deviceId)
					.value(SCOPE, siteExternalId)
					.value("name", preset.name())
					.value("pan", preset.pan())
					.value("tilt", preset.tilt())
					.value("zoom", preset.zoom()));
		}
	}
}
