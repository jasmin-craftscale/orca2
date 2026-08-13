package com.lynxis.orca.runtime.readmodel.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;
import com.lynxis.orca.runtime.persistence.Utc;
import com.lynxis.orca.runtime.readmodel.domain.LaneMonitorTables.LaneMonitorRow;

import lombok.RequiredArgsConstructor;

/**
 * The lane-monitor projection's only persistence path.
 *
 * <p>Every read and write goes through the scope seam. The repository reads
 * core's published topology view to seed the lane identity fields, but it never
 * reads execution or work-item tables; those modules publish facts through the
 * readmodel API port instead.
 */
@RequiredArgsConstructor
public class LaneMonitorRepository {

	private static final String SCOPE_COLUMN = "site_external_id";
	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_LIMIT = 500;
	private static final String[] COLUMNS = { "site_external_id", "site_code", "site_is_primary",
			"area_id", "area_external_id", "area_code", "lane_id", "lane_external_id",
			"lane_code", "lane_name", "lane_priority", "is_out_of_service", "traffic_status",
			"traffic_color", "visit_external_id", "plate", "queued_work_item_external_id",
			"queued_work_item_queued_at", "queued_work_item_assignee",
			"queued_work_item_sla_breached_at", "gate_arm", "red_lamp", "orange_lamp",
			"green_lamp", "loop_inputs", "last_device_event_uuid", "last_device_event_type",
			"last_device_observed_at", "updated_at" };

	private final ScopeSeam seam;

	public List<LaneMonitorRow> list(String laneExternalId, Integer limit) {
		int boundedLimit = boundedLimit(limit);
		ScopedSelect select = ScopedSelect.from("lane_monitor")
				.columns(COLUMNS)
				.scopedBy(SCOPE_COLUMN)
				.orderBy("lane_priority")
				.limit(boundedLimit);
		if (laneExternalId != null && !laneExternalId.isBlank()) {
			select.where("lane_external_id = ?", laneExternalId);
		}
		return seam.select(select, (rs, row) -> map(rs));
	}

	public List<LaneMonitorRow> alerts(String laneExternalId, boolean includeOutOfService,
			Integer limit) {
		int boundedLimit = boundedLimit(limit);
		StringBuilder where = new StringBuilder("(traffic_status IN ('MANUAL', 'FAILED') "
				+ "OR queued_work_item_sla_breached_at IS NOT NULL");
		if (includeOutOfService) {
			where.append(" OR is_out_of_service = 1");
		}
		where.append(')');
		List<Object> parameters = new java.util.ArrayList<>();
		if (laneExternalId != null && !laneExternalId.isBlank()) {
			where.append(" AND lane_external_id = ?");
			parameters.add(laneExternalId);
		}
		return seam.select(ScopedSelect.from("lane_monitor")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where(where.toString(), parameters.toArray())
						.orderBy("lane_priority")
						.limit(boundedLimit),
				(rs, row) -> map(rs));
	}

	public boolean seedLane(long laneId) {
		Optional<TopologyLane> topology = topologyOf(laneId);
		if (topology.isEmpty()) {
			return false;
		}
		upsertTopology(topology.get());
		return true;
	}

	public void visitStarted(long laneId, String visitExternalId, String plate) {
		if (!seedLane(laneId)) {
			return;
		}
		seam.update(ScopedUpdate.table("lane_monitor")
				.set("traffic_status", LaneMonitorRow.ACTIVE)
				.set("traffic_color", LaneMonitorRow.BLUE)
				.set("visit_external_id", visitExternalId)
				.set("plate", plate)
				.set("queued_work_item_external_id", null)
				.set("queued_work_item_queued_at", null)
				.set("queued_work_item_assignee", null)
				.set("queued_work_item_sla_breached_at", null)
				.set("updated_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ?", laneId));
	}

	public void visitClosed(long laneId, String visitExternalId, String status) {
		if (!seedLane(laneId)) {
			return;
		}
		seam.update(ScopedUpdate.table("lane_monitor")
				.set("traffic_status", status)
				.set("traffic_color", colorOf(status))
				.set("queued_work_item_external_id", null)
				.set("queued_work_item_queued_at", null)
				.set("queued_work_item_assignee", null)
				.set("queued_work_item_sla_breached_at", null)
				.set("updated_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ? AND (visit_external_id = ? OR visit_external_id IS NULL)",
						laneId, visitExternalId));
	}

	public void deviceObserved(long laneId, String eventUuid, String eventType, String attributes,
			Instant occurredAt) {
		if (!seedLane(laneId)) {
			return;
		}
		DeviceSnapshot snapshot = DeviceSnapshot.from(attributes);
		ScopedUpdate update = ScopedUpdate.table("lane_monitor")
				.set("last_device_event_uuid", eventUuid)
				.set("last_device_event_type", eventType)
				.set("last_device_observed_at", Utc.timestampOf(occurredAt))
				.set("updated_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ?", laneId);
		if (snapshot.plate() != null) {
			update.set("plate", snapshot.plate());
		}
		if (snapshot.gateArm() != null) {
			update.set("gate_arm", snapshot.gateArm());
		}
		if (snapshot.redLamp() != null) {
			update.set("red_lamp", snapshot.redLamp());
		}
		if (snapshot.orangeLamp() != null) {
			update.set("orange_lamp", snapshot.orangeLamp());
		}
		if (snapshot.greenLamp() != null) {
			update.set("green_lamp", snapshot.greenLamp());
		}
		if (snapshot.loopInputs() != null) {
			update.set("loop_inputs", snapshot.loopInputs());
		}
		seam.update(update);
	}

	public void workItemQueued(long laneId, String workItemExternalId, Instant queuedAt,
			String assignee, Instant slaBreachedAt) {
		if (!seedLane(laneId)) {
			return;
		}
		seam.update(ScopedUpdate.table("lane_monitor")
				.set("queued_work_item_external_id", workItemExternalId)
				.set("queued_work_item_queued_at", Utc.timestampOf(queuedAt))
				.set("queued_work_item_assignee", assignee)
				.set("queued_work_item_sla_breached_at", Utc.timestampOf(slaBreachedAt))
				.set("updated_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ? AND (queued_work_item_queued_at IS NULL "
						+ "OR queued_work_item_queued_at > ? "
						+ "OR queued_work_item_external_id = ?)",
						laneId, Utc.timestampOf(queuedAt), workItemExternalId));
	}

	public void workItemCleared(long laneId, String workItemExternalId) {
		if (!seedLane(laneId)) {
			return;
		}
		seam.update(ScopedUpdate.table("lane_monitor")
				.set("queued_work_item_external_id", null)
				.set("queued_work_item_queued_at", null)
				.set("queued_work_item_assignee", null)
				.set("queued_work_item_sla_breached_at", null)
				.set("updated_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ? AND queued_work_item_external_id = ?", laneId, workItemExternalId));
	}

	public void workItemBreach(long laneId, String workItemExternalId, Instant slaBreachedAt) {
		if (!seedLane(laneId)) {
			return;
		}
		seam.update(ScopedUpdate.table("lane_monitor")
				.set("queued_work_item_sla_breached_at", Utc.timestampOf(slaBreachedAt))
				.set("updated_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ? AND queued_work_item_external_id = ?", laneId, workItemExternalId));
	}

	private Optional<TopologyLane> topologyOf(long laneId) {
		return seam.select(ScopedSelect.from("core.topology_lane")
						.columns("site_external_id", "site_code", "site_is_primary", "area_id",
								"area_external_id", "area_code", "lane_id", "lane_external_id",
								"lane_code", "lane_name", "lane_priority", "is_out_of_service")
						.scopedBy(SCOPE_COLUMN)
						.where("lane_id = ?", laneId),
				(rs, row) -> new TopologyLane(
						rs.getString("site_external_id"),
						rs.getString("site_code"),
						rs.getBoolean("site_is_primary"),
						nullableLong(rs, "area_id"),
						rs.getString("area_external_id"),
						rs.getString("area_code"),
						rs.getLong("lane_id"),
						rs.getString("lane_external_id"),
						rs.getString("lane_code"),
						rs.getString("lane_name"),
						nullableInteger(rs, "lane_priority"),
						rs.getBoolean("is_out_of_service")))
				.stream().findFirst();
	}

	private void upsertTopology(TopologyLane topology) {
		if (updateTopology(topology) == 1) {
			return;
		}
		try {
			seam.insert(ScopedInsert.into("lane_monitor")
					.scopedBy(SCOPE_COLUMN)
					.value("site_external_id", topology.siteExternalId())
					.value("site_code", topology.siteCode())
					.value("site_is_primary", topology.primary())
					.value("area_id", topology.areaId())
					.value("area_external_id", topology.areaExternalId())
					.value("area_code", topology.areaCode())
					.value("lane_id", topology.laneId())
					.value("lane_external_id", topology.laneExternalId())
					.value("lane_code", topology.laneCode())
					.value("lane_name", topology.laneName())
					.value("lane_priority", topology.lanePriority())
					.value("is_out_of_service", topology.outOfService())
					.value("traffic_status", LaneMonitorRow.CLEAR)
					.value("traffic_color", LaneMonitorRow.NEUTRAL));
		}
		catch (DuplicateKeyException raced) {
			updateTopology(topology);
		}
	}

	private int updateTopology(TopologyLane topology) {
		return seam.update(ScopedUpdate.table("lane_monitor")
				.set("site_code", topology.siteCode())
				.set("site_is_primary", topology.primary())
				.set("area_id", topology.areaId())
				.set("area_external_id", topology.areaExternalId())
				.set("area_code", topology.areaCode())
				.set("lane_external_id", topology.laneExternalId())
				.set("lane_code", topology.laneCode())
				.set("lane_name", topology.laneName())
				.set("lane_priority", topology.lanePriority())
				.set("is_out_of_service", topology.outOfService())
				.set("updated_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ?", topology.laneId()));
	}

	private static String colorOf(String status) {
		return switch (status) {
			case LaneMonitorRow.COMPLETED -> LaneMonitorRow.GREEN;
			case LaneMonitorRow.MANUAL -> LaneMonitorRow.AMBER;
			case LaneMonitorRow.FAILED -> LaneMonitorRow.RED;
			case LaneMonitorRow.ACTIVE -> LaneMonitorRow.BLUE;
			default -> LaneMonitorRow.NEUTRAL;
		};
	}

	private static LaneMonitorRow map(ResultSet rs) throws SQLException {
		return new LaneMonitorRow(
				rs.getString("site_external_id"),
				rs.getString("site_code"),
				rs.getBoolean("site_is_primary"),
				nullableLong(rs, "area_id"),
				rs.getString("area_external_id"),
				rs.getString("area_code"),
				rs.getLong("lane_id"),
				rs.getString("lane_external_id"),
				rs.getString("lane_code"),
				rs.getString("lane_name"),
				nullableInteger(rs, "lane_priority"),
				rs.getBoolean("is_out_of_service"),
				rs.getString("traffic_status"),
				rs.getString("traffic_color"),
				rs.getString("visit_external_id"),
				rs.getString("plate"),
				rs.getString("queued_work_item_external_id"),
				Utc.instantAt(rs, "queued_work_item_queued_at"),
				rs.getString("queued_work_item_assignee"),
				Utc.instantAt(rs, "queued_work_item_sla_breached_at"),
				rs.getString("gate_arm"),
				rs.getString("red_lamp"),
				rs.getString("orange_lamp"),
				rs.getString("green_lamp"),
				rs.getString("loop_inputs"),
				rs.getString("last_device_event_uuid"),
				rs.getString("last_device_event_type"),
				Utc.instantAt(rs, "last_device_observed_at"),
				Utc.instantAt(rs, "updated_at"));
	}

	private static int boundedLimit(Integer limit) {
		return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private record TopologyLane(
			String siteExternalId,
			String siteCode,
			boolean primary,
			Long areaId,
			String areaExternalId,
			String areaCode,
			long laneId,
			String laneExternalId,
			String laneCode,
			String laneName,
			Integer lanePriority,
			boolean outOfService) {
	}

	private record DeviceSnapshot(
			String plate,
			String gateArm,
			String redLamp,
			String orangeLamp,
			String greenLamp,
			String loopInputs) {

		private static final Pattern FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|true|false|-?\\d+(?:\\.\\d+)?)");

		static DeviceSnapshot from(String attributes) {
			if (attributes == null || attributes.isBlank()) {
				return new DeviceSnapshot(null, null, null, null, null, null);
			}
			Map<String, String> values = new LinkedHashMap<>();
			Matcher matcher = FIELD.matcher(attributes);
			while (matcher.find()) {
				values.put(matcher.group(1), unquote(matcher.group(2)));
			}
			Map<String, String> loops = new LinkedHashMap<>();
			for (Map.Entry<String, String> value : values.entrySet()) {
				if (value.getKey().toLowerCase(java.util.Locale.ROOT).contains("loop")) {
					loops.put(value.getKey(), value.getValue());
				}
			}
			return new DeviceSnapshot(
					values.get("plate"),
					values.get("gate_arm"),
					values.get("red_lamp"),
					values.get("orange_lamp"),
					values.get("green_lamp"),
					loops.isEmpty() ? null : toJson(loops));
		}

		private static String unquote(String value) {
			return value.startsWith("\"") && value.endsWith("\"")
					? value.substring(1, value.length() - 1)
					: value;
		}

		private static String toJson(Map<String, String> values) {
			StringBuilder json = new StringBuilder("{");
			int index = 0;
			for (Map.Entry<String, String> value : values.entrySet()) {
				if (index++ > 0) {
					json.append(',');
				}
				json.append('"').append(escape(value.getKey())).append("\":\"")
						.append(escape(value.getValue())).append('"');
			}
			return json.append('}').toString();
		}

		private static String escape(String value) {
			return value.replace("\\", "\\\\").replace("\"", "\\\"");
		}
	}
}
