import { Component, DestroyRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import {
  ConfirmDialog,
  DataGrid,
  ElapsedTimer,
  OrcaColumn,
  PageShell,
  StatusChip,
  ToastService,
} from 'ui-registry';
import { runtime } from 'api-client';
import { WorkItemsApi } from './work-items.service';

const POLL_INTERVAL_MS = 5000;

type PendingAction =
  | { kind: 'take'; item: runtime.WorkItem }
  | { kind: 'takeover'; item: runtime.WorkItem }
  | { kind: 'park'; item: runtime.WorkItem }
  | { kind: 'assign'; item: runtime.WorkItem; assigneeExternalId: string };

const ACTION_LABEL: Record<PendingAction['kind'], string> = {
  take: 'take',
  takeover: 'take over',
  park: 'park',
  assign: 'assign',
};

/**
 * `WorkItem` carries no plate — 1.x shows the plate because that is how an operator
 * identifies the truck at the barrier, but neither the API nor the `work_item` table
 * has one. Showing `visitExternalId` instead, per the WP1 plan §7.4. Contract request:
 * the work-item list should carry the plate (avoids an N+1 against visits here).
 */
@Component({
  selector: 'app-work-item-queue-page',
  imports: [PageShell, DataGrid, OrcaColumn, StatusChip, ElapsedTimer, ConfirmDialog],
  template: `
    <orca-page-shell title="Work item queue">
      <div class="flex flex-col gap-8">
        <section>
          <h2 class="mb-3 text-[14px] font-bold text-ink-heading">Queued</h2>
          <orca-data-grid
            [rows]="queuedRows()"
            [loading]="queued.isLoading()"
            [rowClass]="slaRowClass"
            emptyMessage="No queued work items."
          >
            <ng-template orcaColumn="workItem" header="Work item" let-row>{{ row.externalId }}</ng-template>
            <ng-template orcaColumn="lane" header="Lane" let-row>{{ row.laneExternalId }}</ng-template>
            <ng-template orcaColumn="visit" header="Visit" let-row>{{ row.visitExternalId }}</ng-template>
            <ng-template orcaColumn="elapsed" header="Elapsed" let-row>
              @if (row.queuedAt) {
                <orca-elapsed-timer [since]="row.queuedAt" />
              }
            </ng-template>
            <ng-template orcaColumn="status" header="Status" let-row>
              @if (row.status) {
                <orca-status-chip [status]="row.status" />
              }
            </ng-template>
            <ng-template orcaColumn="actions" header="" let-row>
              <div class="flex items-center gap-2">
                <button type="button" class="text-[13px] font-semibold text-primary" (click)="requestTake(row)">
                  Take
                </button>
                <select
                  #assigneeSelect
                  class="rounded-base border border-edge px-1 py-1 text-[13px]"
                  [attr.aria-label]="'Assign ' + row.externalId + ' to'"
                  (change)="onAssigneeSelectChange()"
                >
                  <option value="">Assign to…</option>
                  @for (op of operatorRows(); track op.userExternalId) {
                    <option [value]="op.userExternalId">{{ op.userExternalId }}</option>
                  }
                </select>
                <button
                  type="button"
                  class="text-[13px] font-semibold text-primary disabled:text-ink-placeholder"
                  [disabled]="!assigneeSelect.value"
                  (click)="requestAssign(row, assigneeSelect.value)"
                >
                  Assign
                </button>
                <button type="button" class="text-[13px] font-semibold text-ink-label" (click)="requestPark(row)">
                  Park
                </button>
              </div>
            </ng-template>
          </orca-data-grid>
        </section>

        <section>
          <h2 class="mb-3 text-[14px] font-bold text-ink-heading">In progress</h2>
          <orca-data-grid
            [rows]="inProgressRows()"
            [loading]="inProgress.isLoading()"
            [rowClass]="slaRowClass"
            emptyMessage="No work items in progress."
          >
            <ng-template orcaColumn="workItem" header="Work item" let-row>{{ row.externalId }}</ng-template>
            <ng-template orcaColumn="lane" header="Lane" let-row>{{ row.laneExternalId }}</ng-template>
            <ng-template orcaColumn="visit" header="Visit" let-row>{{ row.visitExternalId }}</ng-template>
            <ng-template orcaColumn="elapsed" header="Elapsed" let-row>
              @if (row.startedAt) {
                <orca-elapsed-timer [since]="row.startedAt" />
              }
            </ng-template>
            <ng-template orcaColumn="status" header="Status" let-row>
              @if (row.status) {
                <orca-status-chip [status]="row.status" />
              }
            </ng-template>
            <ng-template orcaColumn="actions" header="" let-row>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class="text-[13px] font-semibold text-primary"
                  (click)="requestTakeover(row)"
                >
                  Take over
                </button>
                <button type="button" class="text-[13px] font-semibold text-ink-label" (click)="requestPark(row)">
                  Park
                </button>
              </div>
            </ng-template>
          </orca-data-grid>
        </section>
      </div>
    </orca-page-shell>

    <orca-confirm-dialog
      [title]="dialogTitle()"
      [message]="dialogMessage()"
      confirmLabel="Confirm"
      (confirmed)="onConfirmed()"
      (cancelled)="onCancelled()"
    />
  `,
})
export class WorkItemQueuePage {
  private readonly api = inject(WorkItemsApi);
  private readonly presenceApi = inject(runtime.PresenceService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly queued = rxResource({
    stream: () => this.api.list('QUEUED'),
    defaultValue: undefined,
  });

  protected readonly inProgress = rxResource({
    stream: () => this.api.list('IN_PROGRESS'),
    defaultValue: undefined,
  });

  private readonly operators = rxResource({
    stream: () => this.presenceApi.listAssignableOperators(),
    defaultValue: undefined,
  });

  // hasValue() returns false in the error state without reading value(), so each
  // computed here is safe to read from the template on every path.
  protected readonly queuedRows = computed(() =>
    this.queued.hasValue() ? (this.queued.value()?.data ?? []) : [],
  );
  protected readonly inProgressRows = computed(() =>
    this.inProgress.hasValue() ? (this.inProgress.value()?.data ?? []) : [],
  );
  protected readonly operatorRows = computed(() =>
    this.operators.hasValue() ? (this.operators.value()?.data ?? []) : [],
  );

  protected readonly slaRowClass = (row: runtime.WorkItem) =>
    row.slaBreachedAt ? 'bg-negative-row border-negative-edge' : '';

  private readonly confirmDialog = viewChild.required(ConfirmDialog);
  private readonly pending = signal<PendingAction | null>(null);

  // Toast bookkeeping only — never read by a template, so a plain field (not a
  // signal) is fine here. Fires once per error onset, not on every poll while down.
  private queuedWasError = false;
  private inProgressWasError = false;

  protected readonly dialogTitle = computed(() => {
    const p = this.pending();
    switch (p?.kind) {
      case 'take':
        return 'Take this item?';
      case 'takeover':
        return 'Take over this item?';
      case 'park':
        return 'Park this item?';
      case 'assign':
        return 'Assign this item?';
      default:
        return '';
    }
  });

  protected readonly dialogMessage = computed(() => {
    const p = this.pending();
    if (!p) return '';
    switch (p.kind) {
      case 'take':
        return `You will be assigned to ${p.item.externalId} and it will move to In progress.`;
      case 'takeover':
        return `You will take over ${p.item.externalId} from its current assignee.`;
      case 'park':
        return `${p.item.externalId} will be parked and removed from the active queue.`;
      case 'assign':
        return `${p.item.externalId} will be assigned to ${p.assigneeExternalId}.`;
    }
  });

  constructor() {
    // One error path for the whole page: a failed poll becomes a toast, the grids
    // just keep showing their last-known (or empty) rows via hasValue()'s guard.
    effect(() => {
      const isError = this.queued.status() === 'error';
      if (isError && !this.queuedWasError) {
        this.toast.show('Could not load the queued work items.', 'error');
      }
      this.queuedWasError = isError;
    });
    effect(() => {
      const isError = this.inProgress.status() === 'error';
      if (isError && !this.inProgressWasError) {
        this.toast.show('Could not load the in-progress work items.', 'error');
      }
      this.inProgressWasError = isError;
    });

    const intervalId = setInterval(() => {
      this.queued.reload();
      this.inProgress.reload();
    }, POLL_INTERVAL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(intervalId));
  }

  protected requestTake(item: runtime.WorkItem): void {
    this.pending.set({ kind: 'take', item });
    this.confirmDialog().open();
  }

  protected requestTakeover(item: runtime.WorkItem): void {
    this.pending.set({ kind: 'takeover', item });
    this.confirmDialog().open();
  }

  protected requestPark(item: runtime.WorkItem): void {
    this.pending.set({ kind: 'park', item });
    this.confirmDialog().open();
  }

  protected requestAssign(item: runtime.WorkItem, assigneeExternalId: string): void {
    if (!assigneeExternalId) return;
    this.pending.set({ kind: 'assign', item, assigneeExternalId });
    this.confirmDialog().open();
  }

  /**
   * The app is zoneless: the native `<select>`'s own change event doesn't schedule a
   * change-detection pass by itself. The Assign button's `[disabled]` binding reads
   * `assigneeSelect.value` directly (a DOM property, not a signal), so without an
   * Angular event binding on the select, that read goes stale until something else
   * unrelated triggers CD — up to 5s away, via the poll. This handler's only job is
   * to give the zoneless scheduler that trigger immediately.
   */
  protected onAssigneeSelectChange(): void {}

  protected async onConfirmed(): Promise<void> {
    const p = this.pending();
    const id = p?.item.externalId;
    if (!p || !id) return;

    try {
      switch (p.kind) {
        case 'take':
          await this.api.take(id);
          this.queued.reload();
          this.inProgress.reload();
          await this.router.navigate(['/operations/work-items', id]);
          break;
        case 'takeover':
          await this.api.takeover(id);
          this.queued.reload();
          this.inProgress.reload();
          await this.router.navigate(['/operations/work-items', id]);
          break;
        case 'park':
          await this.api.park(id);
          this.queued.reload();
          this.inProgress.reload();
          break;
        case 'assign':
          await this.api.assign(id, p.assigneeExternalId);
          this.queued.reload();
          this.inProgress.reload();
          break;
      }
    } catch {
      this.toast.show(`Could not ${ACTION_LABEL[p.kind]} that work item.`, 'error');
    } finally {
      this.pending.set(null);
    }
  }

  protected onCancelled(): void {
    this.pending.set(null);
  }
}
