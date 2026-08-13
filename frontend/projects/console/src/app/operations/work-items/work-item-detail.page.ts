import { Component, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { ConfirmDialog, DataGrid, OrcaColumn, PageShell, StatusChip, ToastService } from 'ui-registry';
import { WorkItemsApi } from './work-items.service';

type PendingAction = 'complete' | 'park';

/**
 * Do not build the data-driven screen renderer here — the screen layout artifacts
 * do not exist until stream 5. This form is what the renderer replaces later, behind
 * the same route.
 */
@Component({
  selector: 'app-work-item-detail-page',
  imports: [PageShell, DataGrid, OrcaColumn, StatusChip, ConfirmDialog],
  template: `
    <orca-page-shell title="Work item" [subtitle]="id()">
      @if (workItem(); as wi) {
        <div class="flex flex-col gap-6">
          <dl class="grid grid-cols-2 gap-x-8 gap-y-3 text-[13px] sm:grid-cols-4">
            <div>
              <dt class="text-ink-label">Status</dt>
              <dd class="mt-1">
                @if (wi.status) {
                  <orca-status-chip [status]="wi.status" />
                }
              </dd>
            </div>
            <div>
              <dt class="text-ink-label">Visit</dt>
              <dd class="mt-1 text-ink">{{ wi.visitExternalId }}</dd>
            </div>
            <div>
              <dt class="text-ink-label">Lane</dt>
              <dd class="mt-1 text-ink">{{ wi.laneExternalId }}</dd>
            </div>
            <div>
              <dt class="text-ink-label">Assignee</dt>
              <dd class="mt-1 text-ink">{{ wi.assignee ?? '—' }}</dd>
            </div>
          </dl>

          <div>
            <h2 class="mb-2 text-[14px] font-bold text-ink-heading">Event data</h2>
            <pre class="overflow-x-auto rounded-card bg-surface-subtle p-4 text-[12px] text-ink">{{ wi.eventData }}</pre>
          </div>

          <div>
            <h2 class="mb-2 text-[14px] font-bold text-ink-heading">Completion</h2>
            <textarea
              class="w-full rounded-base border border-edge p-3 font-mono text-[12px]"
              rows="8"
              aria-label="Corrected event data"
              [value]="correctedEventData()"
              (input)="onEdit($any($event.target).value)"
            ></textarea>
            <div class="mt-3 flex gap-3">
              <button
                type="button"
                class="rounded-base bg-primary px-4 py-2 text-[13px] font-semibold text-white"
                (click)="requestComplete()"
              >
                Complete
              </button>
              <button
                type="button"
                class="rounded-base px-4 py-2 text-[13px] font-semibold text-ink-label"
                (click)="requestPark()"
              >
                Park
              </button>
            </div>
          </div>

          <div>
            <h2 class="mb-2 text-[14px] font-bold text-ink-heading">Audit trail</h2>
            <orca-data-grid [rows]="auditRows()" [loading]="audit.isLoading()" emptyMessage="No audit history yet.">
              <ng-template orcaColumn="action" header="Action" let-row>{{ row.action }}</ng-template>
              <ng-template orcaColumn="actor" header="Actor" let-row>{{ row.actor }}</ng-template>
              <ng-template orcaColumn="occurredAt" header="Occurred at" let-row>{{ row.occurredAt }}</ng-template>
              <ng-template orcaColumn="elapsedSec" header="Elapsed (s)" let-row>{{ row.elapsedSec }}</ng-template>
            </orca-data-grid>
          </div>
        </div>
      }
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
export class WorkItemDetailPage {
  readonly id = input.required<string>();

  private readonly api = inject(WorkItemsApi);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly item = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
    defaultValue: undefined,
  });

  protected readonly audit = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.audit(params),
    defaultValue: undefined,
  });

  protected readonly workItem = computed(() => (this.item.hasValue() ? this.item.value()?.data : undefined));
  protected readonly auditRows = computed(() =>
    this.audit.hasValue() ? (this.audit.value()?.data ?? []) : [],
  );

  protected readonly correctedEventData = signal('');
  private readonly touched = signal(false);

  private readonly confirmDialog = viewChild.required(ConfirmDialog);
  private readonly pending = signal<PendingAction | null>(null);

  // Toast bookkeeping only — never read by a template, so a plain field is fine.
  private itemWasError = false;
  private auditWasError = false;

  protected readonly dialogTitle = computed(() =>
    this.pending() === 'complete' ? 'Complete this item?' : this.pending() === 'park' ? 'Park this item?' : '',
  );
  protected readonly dialogMessage = computed(() =>
    this.pending() === 'complete'
      ? 'The corrected event data will be saved and the item marked complete.'
      : this.pending() === 'park'
        ? 'This item will be parked and removed from the active queue.'
        : '',
  );

  constructor() {
    // Prefill the textarea once from the loaded item — never clobber an in-progress edit.
    effect(() => {
      const wi = this.workItem();
      if (wi && !this.touched()) {
        this.correctedEventData.set(wi.correctedEventData ?? wi.eventData ?? '');
      }
    });

    // One error path: a failed fetch becomes a toast, the page stays usable.
    effect(() => {
      const isError = this.item.status() === 'error';
      if (isError && !this.itemWasError) {
        this.toast.show('Could not load that work item.', 'error');
      }
      this.itemWasError = isError;
    });
    effect(() => {
      const isError = this.audit.status() === 'error';
      if (isError && !this.auditWasError) {
        this.toast.show('Could not load its audit trail.', 'error');
      }
      this.auditWasError = isError;
    });
  }

  protected onEdit(value: string): void {
    this.touched.set(true);
    this.correctedEventData.set(value);
  }

  protected requestComplete(): void {
    this.pending.set('complete');
    this.confirmDialog().open();
  }

  protected requestPark(): void {
    this.pending.set('park');
    this.confirmDialog().open();
  }

  protected async onConfirmed(): Promise<void> {
    const action = this.pending();
    const id = this.id();
    if (!action) return;

    try {
      if (action === 'complete') {
        await this.api.complete(id, this.correctedEventData());
      } else {
        await this.api.park(id);
      }
      await this.router.navigate(['/operations/work-items']);
    } catch {
      this.toast.show(`Could not ${action} that work item.`, 'error');
    } finally {
      this.pending.set(null);
    }
  }

  protected onCancelled(): void {
    this.pending.set(null);
  }
}
