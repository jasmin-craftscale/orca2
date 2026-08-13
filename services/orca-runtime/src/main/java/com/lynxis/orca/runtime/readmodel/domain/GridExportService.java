package com.lynxis.orca.runtime.readmodel.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.lynxis.orca.runtime.readmodel.domain.GridExportTables.GridExportJob;
import com.lynxis.orca.runtime.readmodel.domain.LaneMonitorTables.LaneMonitorRow;
import com.lynxis.orca.runtime.readmodel.persistence.GridExportRepository;
import com.lynxis.orca.runtime.workitem.api.WorkItemGridPort;

import lombok.RequiredArgsConstructor;

/** Builds bounded CSV exports from the same grid reads the console uses. */
@RequiredArgsConstructor
public class GridExportService {

	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_LIMIT = 500;
	private static final Duration DEFAULT_COMPLETED_WINDOW = Duration.ofHours(24);

	private final GridExportRepository repository;
	private final LaneMonitorService laneMonitors;
	private final WorkItemGridPort workItems;
	private final String siteExternalId;

	public GridExportJob start(GridRequest request) {
		GridRequest normalized = request.normalized();
		Csv csv = csvOf(normalized);
		String externalId = "grid-export-" + UUID.randomUUID();
		String fileName = normalized.gridName().toLowerCase(Locale.ROOT).replace('_', '-')
				+ "-" + externalId + ".csv";
		return repository.insertCompleted(externalId, siteExternalId, normalized.gridName(), fileName,
				csv.rowCount(), csv.body());
	}

	public GridExportJob get(String exportExternalId) {
		return repository.byExternalId(exportExternalId)
				.orElseThrow(() -> new GridExportNotFoundException(exportExternalId));
	}

	private Csv csvOf(GridRequest request) {
		return switch (request.gridName()) {
			case "LANE_MONITORS" -> laneMonitorCsv(
					laneMonitors.list(request.laneExternalId(), request.limit()));
			case "ALERTS" -> alertCsv(laneMonitors.alerts(request.laneExternalId(),
					request.includeOutOfService(), request.limit()));
			case "QUEUE" -> workItemCsv(workItems.openQueue(request.laneExternalId(),
					request.assignee(), request.teamExternalId(), request.limit()));
			case "COMPLETED_WORK" -> workItemCsv(workItems.completedWork(request.status(),
					request.laneExternalId(), request.assignee(), request.completedFrom(),
					request.completedUntil(), request.limit()));
			default -> throw new IllegalArgumentException("Unknown grid '" + request.gridName() + "'.");
		};
	}

	private static Csv laneMonitorCsv(List<LaneMonitorRow> rows) {
		List<List<Object>> values = rows.stream()
				.map(row -> List.<Object>of(
						row.laneExternalId(),
						value(row.laneCode()),
						value(row.laneName()),
						value(row.lanePriority()),
						row.trafficStatus(),
						row.trafficColor(),
						value(row.visitExternalId()),
						value(row.plate()),
						value(row.queuedWorkItemExternalId()),
						value(row.gateArm()),
						value(row.loopInputs()),
						value(row.updatedAt())))
				.toList();
		return csv(List.of("laneExternalId", "laneCode", "laneName", "lanePriority",
				"trafficStatus", "trafficColor", "visitExternalId", "plate",
				"queuedWorkItemExternalId", "gateArm", "loopInputs", "updatedAt"), values);
	}

	private static Csv alertCsv(List<LaneMonitorRow> rows) {
		List<List<Object>> values = rows.stream()
				.map(row -> List.<Object>of(
						row.laneExternalId(),
						value(row.laneCode()),
						value(row.laneName()),
						value(row.lanePriority()),
						row.trafficStatus(),
						row.trafficColor(),
						String.join("|", alertReasons(row)),
						value(row.visitExternalId()),
						value(row.queuedWorkItemExternalId()),
						value(row.queuedWorkItemSlaBreachedAt()),
						row.outOfService()))
				.toList();
		return csv(List.of("laneExternalId", "laneCode", "laneName", "lanePriority",
				"trafficStatus", "trafficColor", "alertReasons", "visitExternalId",
				"queuedWorkItemExternalId", "queuedWorkItemSlaBreachedAt", "outOfService"), values);
	}

	private static Csv workItemCsv(List<WorkItemGridPort.GridItem> rows) {
		List<List<Object>> values = rows.stream()
				.map(row -> List.<Object>of(
						row.externalId(),
						row.visitExternalId(),
						row.laneExternalId(),
						row.processDefinitionKey(),
						row.nodeReference(),
						value(row.screenExternalId()),
						row.status(),
						value(row.assignee()),
						value(row.queuedAt()),
						value(row.startedAt()),
						value(row.completedAt()),
						value(row.completionDurationSec()),
						value(row.slaBreachedAt())))
				.toList();
		return csv(List.of("externalId", "visitExternalId", "laneExternalId",
				"processDefinitionKey", "nodeReference", "screenExternalId", "status",
				"assignee", "queuedAt", "startedAt", "completedAt", "completionDurationSec",
				"slaBreachedAt"), values);
	}

	public static List<String> alertReasons(LaneMonitorRow row) {
		List<String> reasons = new ArrayList<>();
		if (LaneMonitorRow.MANUAL.equals(row.trafficStatus())) {
			reasons.add("MANUAL");
		}
		if (LaneMonitorRow.FAILED.equals(row.trafficStatus())) {
			reasons.add("FAILED");
		}
		if (row.queuedWorkItemSlaBreachedAt() != null) {
			reasons.add("SLA_BREACH");
		}
		if (row.outOfService()) {
			reasons.add("OUT_OF_SERVICE");
		}
		return List.copyOf(reasons);
	}

	private static Csv csv(List<String> headers, List<List<Object>> rows) {
		StringBuilder body = new StringBuilder();
		appendLine(body, headers);
		for (List<Object> row : rows) {
			appendLine(body, row);
		}
		return new Csv(body.toString(), rows.size());
	}

	private static void appendLine(StringBuilder body, List<?> values) {
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				body.append(',');
			}
			body.append(csvValue(values.get(i)));
		}
		body.append('\n');
	}

	private static Object value(Object value) {
		return value == null ? "" : value;
	}

	private static String csvValue(Object value) {
		String text = value == null ? "" : String.valueOf(value);
		if (text.contains(",") || text.contains("\"") || text.contains("\n")
				|| text.contains("\r")) {
			return "\"" + text.replace("\"", "\"\"") + "\"";
		}
		return text;
	}

	private record Csv(String body, int rowCount) {
	}

	public record GridRequest(
			String gridName,
			String laneExternalId,
			String assignee,
			String teamExternalId,
			String status,
			Instant completedFrom,
			Instant completedUntil,
			boolean includeOutOfService,
			Integer limit) {

		private GridRequest normalized() {
			String normalizedGrid = gridName == null ? "LANE_MONITORS" : gridName;
			String normalizedStatus = "FAILED".equals(status) ? "FAILED" : "COMPLETED";
			Instant from = completedFrom;
			if ("COMPLETED_WORK".equals(normalizedGrid) && from == null) {
				from = Instant.now().minus(DEFAULT_COMPLETED_WINDOW);
			}
			return new GridRequest(normalizedGrid, blankToNull(laneExternalId),
					blankToNull(assignee), blankToNull(teamExternalId), normalizedStatus,
					from, completedUntil, includeOutOfService, boundedLimit(limit));
		}
	}

	public static int boundedLimit(Integer limit) {
		return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
	}

	public static Instant completedFromOrDefault(Instant completedFrom) {
		return completedFrom == null ? Instant.now().minus(DEFAULT_COMPLETED_WINDOW) : completedFrom;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	public static class GridExportNotFoundException extends RuntimeException {

		public GridExportNotFoundException(String externalId) {
			super("No grid export '" + externalId + "' exists under this installation's scope.");
		}
	}
}
