package com.lynxis.orca.runtime.execution.engine.flowable;

import com.lynxis.orca.runtime.execution.engine.BpmnDefinition;
import com.lynxis.orca.runtime.execution.engine.ClerkTask;
import com.lynxis.orca.runtime.execution.engine.ClerkTaskEngine;
import com.lynxis.orca.runtime.execution.engine.DeployableDefinition;
import com.lynxis.orca.runtime.execution.engine.EngineDeployment;
import com.lynxis.orca.runtime.execution.engine.EngineInstanceRef;
import com.lynxis.orca.runtime.execution.engine.InstanceSnapshot;
import com.lynxis.orca.runtime.execution.engine.InvalidSignalException;
import com.lynxis.orca.runtime.execution.engine.TaskAlreadyClaimedException;
import com.lynxis.orca.runtime.execution.engine.TenantRef;
import com.lynxis.orca.runtime.execution.engine.UnknownDefinitionException;
import com.lynxis.orca.runtime.execution.engine.UnknownInstanceException;
import com.lynxis.orca.runtime.execution.engine.UnknownTaskException;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngine;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngineException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;

/**
 * The Flowable adapter: {@link WorkflowEngine} on an embedded
 * Flowable process engine. The one class D10's reversibility bet is priced in — the
 * contract test runs against this and the in-memory fake <em>unchanged</em>.
 *
 * <p>Mapping decisions, each carrying a scar from the original engine spike:
 * <ul>
 *   <li><b>Definitions deploy untenanted; instances carry the tenant.</b> Flowable's
 *       {@code tenantId} on a start builder <em>selects the definition</em> (the
 *       trap in {@link TenantRef}'s javadoc). Definitions are shared per install, so the
 *       adapter starts by definition id and stamps the instance with
 *       {@code overrideProcessDefinitionTenantId(site_uuid)} — the D7 binding, readable
 *       from runtime and history alike at history level {@code activity} (variables are
 *       not, by V2's history-off rule).</li>
 *   <li><b>C2 stays asserted, not assumed.</b> The engine-side definition id must
 *       round-trip {@code key:version:uuid} within {@value #MAX_DEFINITION_ID_LENGTH}
 *       characters (the engine's own id column width); one over-long key overflows into
 *       ids that silently collide. Deployment of such a key is a typed failure.</li>
 *   <li><b>Waits are engine waits.</b> {@code signal} resumes the execution parked at the
 *       wait activity via {@link RuntimeService#trigger} — no external-worker acquisition
 *       machinery anywhere (D-2026-08-07-3; the whole C2 acquisition defect class from the
 *       spike cannot exist here).</li>
 * </ul>
 */
public final class FlowableWorkflowEngine implements WorkflowEngine, ClerkTaskEngine {

    /** ACT_RE_PROCDEF.ID_ is nvarchar(64); an id past this is a future collision. */
    static final int MAX_DEFINITION_ID_LENGTH = 64;

    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final HistoryService history;
    private final org.flowable.engine.TaskService tasks;

    public FlowableWorkflowEngine(ProcessEngine engine) {
        this(engine.getRepositoryService(), engine.getRuntimeService(),
                engine.getHistoryService(), engine.getTaskService());
    }

    /**
     * Service-by-service, for the one test that cannot be written against a live engine: the
     * claim that loses on the row's version rather than on a visible assignee. That
     * interleaving is real and rare, and a branch nothing exercises is a branch that rots.
     */
    FlowableWorkflowEngine(RepositoryService repository, RuntimeService runtime,
            HistoryService history, org.flowable.engine.TaskService tasks) {
        this.repository = repository;
        this.runtime = runtime;
        this.history = history;
        this.tasks = tasks;
    }

    @Override
    public EngineDeployment deploy(DeployableDefinition definition, TenantRef tenant) {
        if (!(definition instanceof BpmnDefinition bpmn)) {
            throw new WorkflowEngineException(
                    "This engine deploys BpmnDefinition shapes, got " + definition.getClass().getSimpleName());
        }
        Deployment deployment = repository.createDeployment()
                .name(bpmn.definitionKey())
                .addBytes(bpmn.definitionKey() + ".bpmn20.xml", bpmn.bpmnXml())
                .deploy();

        ProcessDefinition deployed = repository.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        if (deployed == null) {
            throw new WorkflowEngineException(
                    "Deployment '" + bpmn.definitionKey() + "' contains no executable process");
        }
        if (!bpmn.definitionKey().equals(deployed.getKey())) {
            throw new WorkflowEngineException("Definition key '" + bpmn.definitionKey()
                    + "' does not match the BPMN process id '" + deployed.getKey()
                    + "' — the compiler must emit them identically (P2)");
        }
        // C2: the engine-side id must round-trip as key:version:uuid within the id column
        // width. Flowable does not fail an over-long key — it silently falls back to a
        // bare-UUID id that no longer carries the key.
        // Assert the structure on every deploy, not just at startup.
        String roundTrippablePrefix = deployed.getKey() + ":" + deployed.getVersion() + ":";
        if (!deployed.getId().startsWith(roundTrippablePrefix)
                || deployed.getId().length() > MAX_DEFINITION_ID_LENGTH) {
            throw new WorkflowEngineException("Definition id '" + deployed.getId()
                    + "' does not round-trip key:version:uuid within " + MAX_DEFINITION_ID_LENGTH
                    + " chars — the engine fell back to an opaque id; shorten the key '"
                    + deployed.getKey() + "'");
        }
        return new EngineDeployment(deployed.getKey(), deployed.getVersion());
    }

    @Override
    public EngineInstanceRef start(String definitionKey, TenantRef tenant, Map<String, Object> correlationKeys) {
        ProcessDefinition latest = repository.createProcessDefinitionQuery()
                .processDefinitionKey(definitionKey)
                .latestVersion()
                .singleResult();
        if (latest == null) {
            throw new UnknownDefinitionException(definitionKey);
        }
        ProcessInstance instance = runtime.createProcessInstanceBuilder()
                .processDefinitionId(latest.getId())
                .overrideProcessDefinitionTenantId(tenant.siteUuid())
                .variables(correlationKeys)
                .start();
        return new EngineInstanceRef(instance.getId());
    }

    @Override
    public void signal(EngineInstanceRef instance, String waitPointId, Map<String, Object> payload) {
        requireLive(instance, "signal");
        // A userTask wait completes through the task service (the manual-outcome payload
        // becomes the variables the compiled outcome gateway routes on); every other wait
        // is a receiveTask/timer execution resumed via trigger. Same seam semantics either
        // way: deliver the event the instance is waiting for.
        org.flowable.task.api.Task manualTask = tasks.createTaskQuery()
                .processInstanceId(instance.value())
                .taskDefinitionKey(waitPointId)
                .singleResult();
        if (manualTask != null) {
            tasks.complete(manualTask.getId(), payload);
            return;
        }
        Execution parked = runtime.createExecutionQuery()
                .processInstanceId(instance.value())
                .activityId(waitPointId)
                .singleResult();
        if (parked == null) {
            throw new InvalidSignalException("Instance '" + instance.value()
                    + "' is not waiting at '" + waitPointId + "' (waiting at: " + activeActivityIds(instance) + ")");
        }
        runtime.trigger(parked.getId(), payload);
    }

    @Override
    public void cancel(EngineInstanceRef instance, String reason) {
        requireLive(instance, "cancel");
        runtime.deleteProcessInstance(instance.value(), reason);
    }

    @Override
    public InstanceSnapshot stateOf(EngineInstanceRef instance) {
        ProcessInstance live = liveInstance(instance);
        if (live != null) {
            // stateOf is two queries, and a visit can finish between them — routinely, when
            // timers fire on the async executor while something polls. The second query then
            // throws "execution doesn't exist", which is not an error: it is the answer
            // "it completed just now", and the history path below gives it properly.
            List<String> active = null;
            try {
                active = activeActivityIds(instance);
            } catch (org.flowable.common.engine.api.FlowableObjectNotFoundException finishedMeanwhile) {
                live = null;
            }
            if (live != null) {
                if (active.isEmpty()) {
                    return InstanceSnapshot.running(new TenantRef(live.getTenantId()));
                }
                return InstanceSnapshot.waitingAt(active.get(0), new TenantRef(live.getTenantId()));
            }
        }
        HistoricProcessInstance finished = finishedInstance(instance);
        if (finished == null) {
            throw new UnknownInstanceException(instance);
        }
        TenantRef tenant = new TenantRef(finished.getTenantId());
        return finished.getDeleteReason() != null
                ? InstanceSnapshot.cancelled(tenant)
                : InstanceSnapshot.completed(tenant);
    }

    // ---------------------------------------------------------------- clerk tasks

    @Override
    public List<ClerkTask> openTasks(EngineInstanceRef instance) {
        return tasks.createTaskQuery()
                .processInstanceId(instance.value())
                .orderByTaskCreateTime().asc()
                .list()
                .stream()
                .map(FlowableWorkflowEngine::toClerkTask)
                .toList();
    }

    @Override
    public Optional<ClerkTask> findTask(String taskId) {
        return Optional.ofNullable(tasks.createTaskQuery().taskId(taskId).singleResult())
                .map(FlowableWorkflowEngine::toClerkTask);
    }

    @Override
    public void claim(String taskId, String assignee) {
        requireOpen(taskId);
        try {
            tasks.claim(taskId, assignee);
        } catch (FlowableTaskAlreadyClaimedException alreadyHeld) {
            // The loser read the winner's assignee.
            throw new TaskAlreadyClaimedException(taskId, alreadyHeld.getTaskAssignee());
        } catch (FlowableOptimisticLockingException lostTheRace) {
            // Both read it unassigned; the row's version rejected this write. Same answer to
            // the caller — the ONLY-ONE-WINNER property lives in the version, not in a WHERE
            // clause somebody has to keep correct (the Go claim's conditional UPDATE).
            throw new TaskAlreadyClaimedException(taskId, findTask(taskId)
                    .flatMap(ClerkTask::assignedTo).orElse(null));
        }
    }

    @Override
    public void release(String taskId) {
        requireOpen(taskId);
        // Idempotent by construction: unclaim is claim(null), which does not object to an
        // already-queued item.
        tasks.unclaim(taskId);
    }

    @Override
    public void reassign(String taskId, String assignee) {
        requireOpen(taskId);
        tasks.setAssignee(taskId, assignee);
    }

    @Override
    public void complete(String taskId, Map<String, Object> payload) {
        requireOpen(taskId);
        // The flow advance happens inside this call's transaction: there is no window where
        // the item is done and the visit still waits at it (D4.3).
        tasks.complete(taskId, payload == null ? Map.of() : payload);
    }

    private void requireOpen(String taskId) {
        if (tasks.createTaskQuery().taskId(taskId).count() == 0) {
            throw new UnknownTaskException(taskId);
        }
    }

    private static ClerkTask toClerkTask(org.flowable.task.api.Task task) {
        return new ClerkTask(
                task.getId(),
                new EngineInstanceRef(task.getProcessInstanceId()),
                task.getTaskDefinitionKey(),
                task.getAssignee(),
                task.getCreateTime() == null ? null : task.getCreateTime().toInstant());
    }

    /** A live instance, or the typed reason there is none: finished vs never existed. */
    private void requireLive(EngineInstanceRef instance, String verb) {
        if (liveInstance(instance) != null) {
            return;
        }
        if (finishedInstance(instance) != null) {
            throw new InvalidSignalException(
                    "Cannot " + verb + " instance '" + instance.value() + "': it already finished");
        }
        throw new UnknownInstanceException(instance);
    }

    private ProcessInstance liveInstance(EngineInstanceRef instance) {
        return runtime.createProcessInstanceQuery()
                .processInstanceId(instance.value())
                .singleResult();
    }

    private HistoricProcessInstance finishedInstance(EngineInstanceRef instance) {
        return history.createHistoricProcessInstanceQuery()
                .processInstanceId(instance.value())
                .finished()
                .singleResult();
    }

    private List<String> activeActivityIds(EngineInstanceRef instance) {
        return runtime.getActiveActivityIds(instance.value());
    }
}
