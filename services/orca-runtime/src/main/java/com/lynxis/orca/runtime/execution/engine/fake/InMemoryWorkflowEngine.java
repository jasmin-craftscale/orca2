package com.lynxis.orca.runtime.execution.engine.fake;

import com.lynxis.orca.runtime.execution.engine.ClerkTask;
import com.lynxis.orca.runtime.execution.engine.ClerkTaskEngine;
import com.lynxis.orca.runtime.execution.engine.DeployableDefinition;
import com.lynxis.orca.runtime.execution.engine.EngineDeployment;
import com.lynxis.orca.runtime.execution.engine.EngineInstanceRef;
import com.lynxis.orca.runtime.execution.engine.InstanceSnapshot;
import com.lynxis.orca.runtime.execution.engine.InstanceState;
import com.lynxis.orca.runtime.execution.engine.InvalidSignalException;
import com.lynxis.orca.runtime.execution.engine.TaskAlreadyClaimedException;
import com.lynxis.orca.runtime.execution.engine.TenantRef;
import com.lynxis.orca.runtime.execution.engine.UnknownDefinitionException;
import com.lynxis.orca.runtime.execution.engine.UnknownInstanceException;
import com.lynxis.orca.runtime.execution.engine.UnknownTaskException;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngine;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngineException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine fake: what keeps the fast suite fast — compiler and
 * delegate tests run against this with no database and no engine boot. It exists so the
 * {@code WorkflowEngine} contract test is written against it <em>first</em>, before Flowable
 * can influence the seam's shape — the reason the fake exists at all.
 *
 * <p>Deliberately synchronous and single-threaded per call; thread-safety is coarse
 * (synchronized) because test convenience, not throughput, is its job.
 */
public final class InMemoryWorkflowEngine implements WorkflowEngine, ClerkTaskEngine {

    private final Map<String, Deployed> deployments = new HashMap<>();
    private final Map<String, Instance> instances = new HashMap<>();
    /** Open clerk tasks, in creation order — the fake's ACT_RU_TASK. */
    private final Map<String, FakeTask> tasks = new LinkedHashMap<>();
    private long instanceSequence;
    private long taskSequence;

    @Override
    public synchronized EngineDeployment deploy(DeployableDefinition definition, TenantRef tenant) {
        if (!(definition instanceof FakeDefinition fake)) {
            throw new WorkflowEngineException(
                    "InMemoryWorkflowEngine deploys FakeDefinition, got "
                            + definition.getClass().getSimpleName());
        }
        Deployed previous = deployments.get(fake.definitionKey());
        int version = previous == null ? 1 : previous.version() + 1;
        deployments.put(fake.definitionKey(), new Deployed(fake, version));
        return new EngineDeployment(fake.definitionKey(), version);
    }

    @Override
    public synchronized EngineInstanceRef start(
            String definitionKey, TenantRef tenant, Map<String, Object> correlationKeys) {
        Deployed deployed = deployments.get(definitionKey);
        if (deployed == null) {
            throw new UnknownDefinitionException(definitionKey);
        }
        EngineInstanceRef ref = new EngineInstanceRef("fake-" + (++instanceSequence));
        Instance instance = new Instance(deployed.definition().steps(), tenant);
        instances.put(ref.value(), instance);
        instance.advance();
        syncTasks(ref, instance);
        return ref;
    }

    @Override
    public synchronized void signal(EngineInstanceRef ref, String waitPointId, Map<String, Object> payload) {
        Instance instance = requireInstance(ref);
        if (instance.state != InstanceState.WAITING) {
            throw new InvalidSignalException(
                    "Instance '" + ref.value() + "' is " + instance.state + ", not waiting");
        }
        if (!instance.currentWaitPoint().equals(waitPointId)) {
            throw new InvalidSignalException(
                    "Instance '" + ref.value() + "' waits at '" + instance.currentWaitPoint()
                            + "', not at '" + waitPointId + "'");
        }
        instance.position++;
        instance.advance();
        syncTasks(ref, instance);
    }

    @Override
    public synchronized void cancel(EngineInstanceRef ref, String reason) {
        Instance instance = requireInstance(ref);
        if (instance.state == InstanceState.COMPLETED || instance.state == InstanceState.CANCELLED) {
            throw new InvalidSignalException(
                    "Instance '" + ref.value() + "' already finished (" + instance.state + ")");
        }
        instance.state = InstanceState.CANCELLED;
        syncTasks(ref, instance);
    }

    @Override
    public synchronized InstanceSnapshot stateOf(EngineInstanceRef ref) {
        Instance instance = requireInstance(ref);
        return switch (instance.state) {
            case RUNNING -> InstanceSnapshot.running(instance.tenant);
            case WAITING -> InstanceSnapshot.waitingAt(instance.currentWaitPoint(), instance.tenant);
            case COMPLETED -> InstanceSnapshot.completed(instance.tenant);
            case CANCELLED -> InstanceSnapshot.cancelled(instance.tenant);
        };
    }

    /** Latest deployed version of a key, for test assertions. */
    public synchronized Optional<Integer> deployedVersion(String definitionKey) {
        return Optional.ofNullable(deployments.get(definitionKey)).map(Deployed::version);
    }

    // ---------------------------------------------------------------- clerk tasks

    @Override
    public synchronized List<ClerkTask> openTasks(EngineInstanceRef ref) {
        requireInstance(ref);
        List<ClerkTask> open = new ArrayList<>();
        tasks.values().stream()
                .filter(task -> task.instance.equals(ref.value()))
                .forEach(task -> open.add(task.snapshot()));
        return List.copyOf(open);
    }

    @Override
    public synchronized Optional<ClerkTask> findTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).map(FakeTask::snapshot);
    }

    @Override
    public synchronized void claim(String taskId, String assignee) {
        FakeTask task = requireTask(taskId);
        if (task.assignee != null && !task.assignee.isBlank()) {
            throw new TaskAlreadyClaimedException(taskId, task.assignee);
        }
        task.assignee = assignee;
    }

    @Override
    public synchronized void release(String taskId) {
        requireTask(taskId).assignee = null;
    }

    @Override
    public synchronized void reassign(String taskId, String assignee) {
        requireTask(taskId).assignee = assignee;
    }

    @Override
    public synchronized void complete(String taskId, Map<String, Object> payload) {
        FakeTask task = requireTask(taskId);
        EngineInstanceRef ref = new EngineInstanceRef(task.instance);
        Instance instance = requireInstance(ref);
        instance.position++;
        instance.advance();
        syncTasks(ref, instance);
    }

    /**
     * Reconciles open tasks with where the instance now stands. A clerk wait has a task while
     * it is parked and none once it moves — the fake's version of the engine's guarantee that
     * an open task and a completed step cannot coexist.
     */
    private void syncTasks(EngineInstanceRef ref, Instance instance) {
        String clerkWait = instance.state == InstanceState.WAITING
                && instance.currentKind() == FakeDefinition.Step.Kind.CLERK_WAIT
                ? instance.currentWaitPoint()
                : null;
        tasks.values().removeIf(task -> task.instance.equals(ref.value())
                && !task.nodeId.equals(clerkWait));
        if (clerkWait == null) {
            return;
        }
        boolean alreadyOpen = tasks.values().stream()
                .anyMatch(task -> task.instance.equals(ref.value()) && task.nodeId.equals(clerkWait));
        if (!alreadyOpen) {
            String taskId = "fake-task-" + (++taskSequence);
            tasks.put(taskId, new FakeTask(taskId, ref.value(), clerkWait, Instant.now()));
        }
    }

    private FakeTask requireTask(String taskId) {
        FakeTask task = tasks.get(taskId);
        if (task == null) {
            throw new UnknownTaskException(taskId);
        }
        return task;
    }

    private static final class FakeTask {

        private final String taskId;
        private final String instance;
        private final String nodeId;
        private final Instant createdAt;
        private String assignee;

        private FakeTask(String taskId, String instance, String nodeId, Instant createdAt) {
            this.taskId = taskId;
            this.instance = instance;
            this.nodeId = nodeId;
            this.createdAt = createdAt;
        }

        private ClerkTask snapshot() {
            return new ClerkTask(taskId, new EngineInstanceRef(instance), nodeId, assignee, createdAt);
        }
    }

    private Instance requireInstance(EngineInstanceRef ref) {
        Instance instance = instances.get(ref.value());
        if (instance == null) {
            throw new UnknownInstanceException(ref);
        }
        return instance;
    }

    private record Deployed(FakeDefinition definition, int version) {
    }

    private static final class Instance {

        private final List<FakeDefinition.Step> steps;
        private final TenantRef tenant;
        private int position;
        private InstanceState state = InstanceState.RUNNING;

        private Instance(List<FakeDefinition.Step> steps, TenantRef tenant) {
            this.steps = steps;
            this.tenant = tenant;
        }

        private void advance() {
            while (position < steps.size()) {
                FakeDefinition.Step step = steps.get(position);
                if (step.kind().parks()) {
                    state = InstanceState.WAITING;
                    return;
                }
                position++;
            }
            state = InstanceState.COMPLETED;
        }

        private String currentWaitPoint() {
            return steps.get(position).id();
        }

        private FakeDefinition.Step.Kind currentKind() {
            return steps.get(position).kind();
        }
    }
}
