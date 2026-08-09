package com.lynxis.orca.runtime.workitem.api;

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
import com.lynxis.orca.runtime.api.generated.WorkItemsApi;
import com.lynxis.orca.runtime.api.generated.model.AssignWorkItemRequest;
import com.lynxis.orca.runtime.api.generated.model.CompleteWorkItemRequest;
import com.lynxis.orca.runtime.api.generated.model.WorkItem;
import com.lynxis.orca.runtime.api.generated.model.WorkItemAuditEntry;
import com.lynxis.orca.runtime.api.generated.model.WorkItemAuditEnvelope;
import com.lynxis.orca.runtime.api.generated.model.WorkItemEnvelope;
import com.lynxis.orca.runtime.api.generated.model.WorkItemListEnvelope;
import com.lynxis.orca.runtime.execution.api.ManualStepPort;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;
import com.lynxis.orca.runtime.workitem.domain.WorkItemTables;

/**
 * The clerk workflow's console surface (§C2's work-item routes).
 *
 * <p>Hand-written, implementing the generated interface — ADR-014. Scope is the
 * installation's own site, from configuration, for the reasons
 * {@code DeviceEventController} states at length; the acting operator is the
 * authenticated caller, never a field of the request — an operator who could name
 * another operator in a claim would be 1.x's audit trail all over again.
 */
@RestController
public class WorkItemController implements WorkItemsApi {

	private final WorkItemService workItems;
	private final OperatorIdentity operatorIdentity;
	private final String siteExternalId;

	public WorkItemController(WorkItemService workItems, OperatorIdentity operatorIdentity,
			String siteExternalId) {
		this.workItems = workItems;
		this.operatorIdentity = operatorIdentity;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public ResponseEntity<WorkItemListEnvelope> listWorkItems(String status, String laneExternalId,
			String assignee, String teamExternalId, Integer limit) {
		List<WorkItemTables.WorkItem> items = inScope(() -> workItems.list(status, laneExternalId,
				assignee, teamExternalId, limit == null ? 100 : Math.min(limit, 500)));
		return ResponseEntity.ok(new WorkItemListEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(items.stream().map(WorkItemController::toModel).toList()));
	}

	@Override
	public ResponseEntity<WorkItemEnvelope> getWorkItem(String workItemExternalId) {
		return item(() -> workItems.get(workItemExternalId));
	}

	@Override
	public ResponseEntity<WorkItemEnvelope> takeWorkItem(String workItemExternalId) {
		String actor = actingOperator();
		return item(() -> workItems.take(workItemExternalId, actor));
	}

	@Override
	public ResponseEntity<WorkItemEnvelope> takeoverWorkItem(String workItemExternalId) {
		String actor = actingOperator();
		return item(() -> workItems.takeover(workItemExternalId, actor));
	}

	@Override
	public ResponseEntity<WorkItemEnvelope> parkWorkItem(String workItemExternalId) {
		String actor = actingOperator();
		return item(() -> workItems.park(workItemExternalId, actor));
	}

	@Override
	public ResponseEntity<WorkItemEnvelope> assignWorkItem(String workItemExternalId,
			AssignWorkItemRequest request) {
		String actor = actingOperator();
		return item(() -> workItems.assign(workItemExternalId, request.getAssigneeExternalId(), actor));
	}

	@Override
	public ResponseEntity<WorkItemEnvelope> completeWorkItem(String workItemExternalId,
			CompleteWorkItemRequest request) {
		String actor = actingOperator();
		String corrected = request == null ? null : request.getCorrectedEventData();
		return item(() -> workItems.complete(workItemExternalId, actor, corrected));
	}

	@Override
	public ResponseEntity<WorkItemAuditEnvelope> getWorkItemAudit(String workItemExternalId) {
		List<WorkItemTables.WorkItemAudit> trail = inScope(() -> workItems.auditTrail(workItemExternalId));
		return ResponseEntity.ok(new WorkItemAuditEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(trail.stream().map(WorkItemController::toModel).toList()));
	}

	// ------------------------------------------------------------------------

	private ResponseEntity<WorkItemEnvelope> item(Supplier<WorkItemTables.WorkItem> action) {
		WorkItemTables.WorkItem item = inScope(action);
		return ResponseEntity.ok(new WorkItemEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(toModel(item)));
	}

	/**
	 * Establishes scope and translates the domain's typed failures into the
	 * envelope's typed codes — the guarded update's 0-rows answer becomes a 409 a
	 * console can branch on, and the engine's "not waiting" becomes the
	 * out-of-order refusal, distinguishable on purpose.
	 */
	private <T> T inScope(Supplier<T> action) {
		try {
			return ScopeContext.callIn(installationScope(), action::get);
		}
		catch (WorkItemService.WorkItemNotFoundException notFound) {
			throw new ApiException(WorkItemErrorCode.WORK_ITEM_NOT_FOUND, notFound.getMessage());
		}
		catch (WorkItemService.WorkItemConflictException conflict) {
			throw new ApiException(WorkItemErrorCode.WORK_ITEM_CONFLICT, conflict.getMessage());
		}
		catch (WorkItemService.WorkItemIneligibleException ineligible) {
			throw new ApiException(WorkItemErrorCode.WORK_ITEM_NOT_ELIGIBLE, ineligible.getMessage());
		}
		catch (ManualStepPort.ProcessNotWaitingException outOfOrder) {
			throw new ApiException(WorkItemErrorCode.WORK_ITEM_OUT_OF_ORDER, outOfOrder.getMessage());
		}
	}

	private String actingOperator() {
		return operatorIdentity.operator().orElseThrow(() -> new ApiException(
				WorkItemErrorCode.OPERATOR_UNRESOLVED,
				"The request carries no resolvable operator identity, and a work-item action "
						+ "without an actor would be an audit trail with a hole in it."));
	}

	private Scope installationScope() {
		return Scope.of("site_external_id", Set.of(siteExternalId));
	}

	private static WorkItem toModel(WorkItemTables.WorkItem item) {
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

	private static WorkItemAuditEntry toModel(WorkItemTables.WorkItemAudit audit) {
		return new WorkItemAuditEntry()
				.action(WorkItemAuditEntry.ActionEnum.fromValue(audit.action()))
				.actor(audit.actor())
				.previousAssignee(audit.previousAssignee())
				.occurredAt(offset(audit.occurredAt()))
				.processingDurationSec(audit.processingDurationSec())
				.elapsedSec(audit.elapsedSec());
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
