package com.lynxis.orca.runtime.readmodel.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.platform.web.ApiResponse;
import com.lynxis.orca.platform.web.ApiStatus;
import com.lynxis.orca.platform.web.RequestId;
import com.lynxis.orca.runtime.api.generated.GridsApi;
import com.lynxis.orca.runtime.api.generated.model.CompletedWorkGridRequest;
import com.lynxis.orca.runtime.api.generated.model.GridExport;
import com.lynxis.orca.runtime.api.generated.model.GridExportEnvelope;
import com.lynxis.orca.runtime.api.generated.model.GridExportRequest;
import com.lynxis.orca.runtime.api.generated.model.GridName;
import com.lynxis.orca.runtime.api.generated.model.LaneAlert;
import com.lynxis.orca.runtime.api.generated.model.LaneAlertListEnvelope;
import com.lynxis.orca.runtime.api.generated.model.LaneAlertReason;
import com.lynxis.orca.runtime.api.generated.model.LaneAlertsGridRequest;
import com.lynxis.orca.runtime.api.generated.model.LaneMonitor;
import com.lynxis.orca.runtime.api.generated.model.LaneMonitorGridRequest;
import com.lynxis.orca.runtime.api.generated.model.LaneMonitorListEnvelope;
import com.lynxis.orca.runtime.api.generated.model.WorkItem;
import com.lynxis.orca.runtime.api.generated.model.WorkItemGridRequest;
import com.lynxis.orca.runtime.api.generated.model.WorkItemListEnvelope;
import com.lynxis.orca.runtime.readmodel.domain.GridExportService;
import com.lynxis.orca.runtime.readmodel.domain.GridExportService.GridRequest;
import com.lynxis.orca.runtime.readmodel.domain.GridExportTables.GridExportJob;
import com.lynxis.orca.runtime.readmodel.domain.LaneMonitorService;
import com.lynxis.orca.runtime.readmodel.domain.LaneMonitorTables.LaneMonitorRow;
import com.lynxis.orca.runtime.workitem.api.WorkItemGridPort;

/** Console grid routes, owned by readmodel and backed by maintained projections or module API seams. */
@RestController
public class GridController implements GridsApi {

	private final LaneMonitorService laneMonitors;
	private final WorkItemGridPort workItems;
	private final GridExportService exports;
	private final String siteExternalId;

	public GridController(LaneMonitorService laneMonitors, WorkItemGridPort workItems,
			GridExportService exports, String siteExternalId) {
		this.laneMonitors = laneMonitors;
		this.workItems = workItems;
		this.exports = exports;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<LaneMonitorListEnvelope> laneMonitorsGrid(LaneMonitorGridRequest request) {
		String laneExternalId = request == null ? null : request.getLaneExternalId();
		Integer limit = request == null ? null : request.getLimit();
		var rows = inScope(() -> laneMonitors.list(laneExternalId, limit));
		return ResponseEntity.ok(new LaneMonitorListEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(rows.stream().map(GridController::toLaneMonitorModel).toList()));
	}

	@Override
	public ResponseEntity<WorkItemListEnvelope> queueGrid(WorkItemGridRequest request) {
		int limit = GridExportService.boundedLimit(request == null ? null : request.getLimit());
		var rows = inScope(() -> workItems.openQueue(
				request == null ? null : request.getLaneExternalId(),
				request == null ? null : request.getAssignee(),
				request == null ? null : request.getTeamExternalId(),
				limit));
		return ResponseEntity.ok(workItemEnvelope(rows));
	}

	@Override
	public ResponseEntity<LaneAlertListEnvelope> alertsGrid(LaneAlertsGridRequest request) {
		String laneExternalId = request == null ? null : request.getLaneExternalId();
		boolean includeOutOfService = request != null && Boolean.TRUE.equals(request.getIncludeOutOfService());
		Integer limit = request == null ? null : request.getLimit();
		var rows = inScope(() -> laneMonitors.alerts(laneExternalId, includeOutOfService, limit));
		return ResponseEntity.ok(new LaneAlertListEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(rows.stream().map(GridController::toAlertModel).toList()));
	}

	@Override
	public ResponseEntity<WorkItemListEnvelope> completedWorkGrid(CompletedWorkGridRequest request) {
		int limit = GridExportService.boundedLimit(request == null ? null : request.getLimit());
		String status = request == null || request.getStatus() == null
				? com.lynxis.orca.runtime.workitem.domain.WorkItemTables.WorkItem.COMPLETED
				: request.getStatus().getValue();
		Instant completedFrom = GridExportService.completedFromOrDefault(
				request == null ? null : instant(request.getCompletedFrom()));
		Instant completedUntil = request == null ? null : instant(request.getCompletedUntil());
		var rows = inScope(() -> workItems.completedWork(status,
				request == null ? null : request.getLaneExternalId(),
				request == null ? null : request.getAssignee(),
				completedFrom, completedUntil, limit));
		return ResponseEntity.ok(workItemEnvelope(rows));
	}

	@Override
	public ResponseEntity<GridExportEnvelope> startGridExport(GridExportRequest request) {
		GridExportJob job = inScope(() -> exports.start(toRequest(request)));
		return ResponseEntity.ok(exportEnvelope(job));
	}

	@Override
	public ResponseEntity<GridExportEnvelope> getGridExport(String exportExternalId) {
		GridExportJob job = inScope(() -> exports.get(exportExternalId));
		return ResponseEntity.ok(exportEnvelope(job));
	}

	private <T> T inScope(Supplier<T> action) {
		try {
			return ScopeContext.callIn(installationScope(), action::get);
		}
		catch (GridExportService.GridExportNotFoundException notFound) {
			throw new ApiException(ReadModelErrorCode.GRID_EXPORT_NOT_FOUND, notFound.getMessage());
		}
	}

	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}

	private static WorkItemListEnvelope workItemEnvelope(List<WorkItemGridPort.GridItem> rows) {
		return new WorkItemListEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(rows.stream().map(GridController::toWorkItemModel).toList());
	}

	private static GridExportEnvelope exportEnvelope(GridExportJob job) {
		return new GridExportEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(new GridExport()
						.exportExternalId(job.externalId())
						.grid(GridName.fromValue(job.gridName()))
						.status(GridExport.StatusEnum.fromValue(job.status()))
						.contentType(job.contentType())
						.fileName(job.fileName())
						.rowCount(job.rowCount())
						.createdAt(offset(job.createdAt()))
						.completedAt(offset(job.completedAt()))
						.body(job.body()));
	}

	private static GridRequest toRequest(GridExportRequest request) {
		return new GridRequest(
				request == null || request.getGrid() == null ? null : request.getGrid().getValue(),
				request == null ? null : request.getLaneExternalId(),
				request == null ? null : request.getAssignee(),
				request == null ? null : request.getTeamExternalId(),
				request == null || request.getStatus() == null ? null : request.getStatus().getValue(),
				request == null ? null : instant(request.getCompletedFrom()),
				request == null ? null : instant(request.getCompletedUntil()),
				request != null && Boolean.TRUE.equals(request.getIncludeOutOfService()),
				request == null ? null : request.getLimit());
	}

	private static LaneMonitor toLaneMonitorModel(LaneMonitorRow row) {
		return new LaneMonitor()
				.siteExternalId(row.siteExternalId())
				.siteCode(row.siteCode())
				.sitePrimary(row.sitePrimary())
				.areaExternalId(row.areaExternalId())
				.areaCode(row.areaCode())
				.laneExternalId(row.laneExternalId())
				.laneCode(row.laneCode())
				.laneName(row.laneName())
				.lanePriority(row.lanePriority())
				.outOfService(row.outOfService())
				.trafficStatus(LaneMonitor.TrafficStatusEnum.fromValue(row.trafficStatus()))
				.trafficColor(LaneMonitor.TrafficColorEnum.fromValue(row.trafficColor()))
				.visitExternalId(row.visitExternalId())
				.plate(row.plate())
				.queuedWorkItemExternalId(row.queuedWorkItemExternalId())
				.queuedWorkItemQueuedAt(offset(row.queuedWorkItemQueuedAt()))
				.queuedWorkItemAssignee(row.queuedWorkItemAssignee())
				.queuedWorkItemSlaBreachedAt(offset(row.queuedWorkItemSlaBreachedAt()))
				.gateArm(row.gateArm())
				.redLamp(row.redLamp())
				.orangeLamp(row.orangeLamp())
				.greenLamp(row.greenLamp())
				.loopInputs(row.loopInputs())
				.lastDeviceEventUuid(row.lastDeviceEventUuid())
				.lastDeviceEventType(row.lastDeviceEventType())
				.lastDeviceObservedAt(offset(row.lastDeviceObservedAt()))
				.updatedAt(offset(row.updatedAt()));
	}

	private static LaneAlert toAlertModel(LaneMonitorRow row) {
		return new LaneAlert()
				.laneExternalId(row.laneExternalId())
				.laneCode(row.laneCode())
				.laneName(row.laneName())
				.lanePriority(row.lanePriority())
				.trafficStatus(LaneAlert.TrafficStatusEnum.fromValue(row.trafficStatus()))
				.trafficColor(LaneAlert.TrafficColorEnum.fromValue(row.trafficColor()))
				.visitExternalId(row.visitExternalId())
				.plate(row.plate())
				.queuedWorkItemExternalId(row.queuedWorkItemExternalId())
				.queuedWorkItemQueuedAt(offset(row.queuedWorkItemQueuedAt()))
				.queuedWorkItemSlaBreachedAt(offset(row.queuedWorkItemSlaBreachedAt()))
				.outOfService(row.outOfService())
				.alertReasons(GridExportService.alertReasons(row).stream()
						.map(LaneAlertReason::fromValue)
						.toList());
	}

	private static WorkItem toWorkItemModel(WorkItemGridPort.GridItem item) {
		return new WorkItem()
				.externalId(item.externalId())
				.visitExternalId(item.visitExternalId())
				.laneExternalId(item.laneExternalId())
				.processDefinitionKey(item.processDefinitionKey())
				.nodeReference(item.nodeReference())
				.screenExternalId(item.screenExternalId())
				.status(WorkItem.StatusEnum.fromValue(item.status()))
				.assignee(item.assignee())
				.queuedAt(offset(item.queuedAt()))
				.startedAt(offset(item.startedAt()))
				.completedAt(offset(item.completedAt()))
				.completionDurationSec(item.completionDurationSec())
				.slaBreachedAt(offset(item.slaBreachedAt()))
				.eventData(item.eventData())
				.correctedEventData(item.correctedEventData());
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
