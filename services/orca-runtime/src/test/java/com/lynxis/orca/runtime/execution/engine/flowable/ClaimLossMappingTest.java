package com.lynxis.orca.runtime.execution.engine.flowable;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynxis.orca.runtime.execution.engine.TaskAlreadyClaimedException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.flowable.engine.TaskService;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.Test;

/**
 * The two ways a claim loses, mapped to one answer.
 *
 * <p>{@code ConcurrentClaimIntegrationTest} runs a thousand real claims and every loser there
 * lost the visible way — it read the winner's assignee. The other way is the interleaving
 * where both clerks read the item unassigned and the row's version rejects the second write;
 * it is rare enough that a live test cannot be relied on to produce it, and dangerous enough
 * that leaking the engine's exception through the seam would surface an optimistic-locking
 * stack trace on a clerk's screen. So it is pinned here, at the mapping.
 */
class ClaimLossMappingTest {

    @Test
    void losingOnTheRowVersionIsTheSameConflictAsLosingOnTheAssignee() {
        TaskService tasks = openTaskService();
        doThrow(new FlowableOptimisticLockingException("ACT_RU_TASK was updated by another transaction"))
                .when(tasks).claim("task-1", "clerk-bo");
        var engine = new FlowableWorkflowEngine(null, null, null, tasks);

        assertThatExceptionOfType(TaskAlreadyClaimedException.class)
                .isThrownBy(() -> engine.claim("task-1", "clerk-bo"));
    }

    @Test
    void losingOnTheAssigneeNamesTheHolder() {
        TaskService tasks = openTaskService();
        doThrow(new FlowableTaskAlreadyClaimedException("task-1", "clerk-anna"))
                .when(tasks).claim("task-1", "clerk-bo");
        var engine = new FlowableWorkflowEngine(null, null, null, tasks);

        assertThatExceptionOfType(TaskAlreadyClaimedException.class)
                .isThrownBy(() -> engine.claim("task-1", "clerk-bo"))
                .withMessageContaining("clerk-anna");
    }

    /** A task service that reports one open task and nothing more. */
    private static TaskService openTaskService() {
        TaskService tasks = mock(TaskService.class);
        TaskQuery query = mock(TaskQuery.class);
        when(tasks.createTaskQuery()).thenReturn(query);
        when(query.taskId(anyString())).thenReturn(query);
        when(query.count()).thenReturn(1L);
        return tasks;
    }
}
