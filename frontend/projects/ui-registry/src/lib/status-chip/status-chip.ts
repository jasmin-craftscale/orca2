import { Component, computed, input } from '@angular/core';

/**
 * Shared vocabulary of every grid and chip. Maps both `WorkItem.status` and
 * `PresenceStatus` to the same token pairs, so an operator reads one color
 * language everywhere. `ACTIVE` is not in either enum's documented mapping —
 * treated as WORKING here; see the WP1 report.
 */
const STATUS_CLASSES: Record<string, string> = {
  QUEUED: 'bg-queued-bg text-queued',
  IN_PROGRESS: 'bg-in-progress-bg text-in-progress',
  COMPLETED: 'bg-positive-bg text-positive-text',
  FAILED: 'bg-negative-bg text-negative',
  IDLE: 'bg-idle-bg text-idle',
  WORKING: 'bg-in-progress-bg text-in-progress',
  BREAK: 'bg-neutral-bg text-neutral',
  DND: 'bg-idle-bg text-idle',
  OFFLINE: 'bg-idle-bg text-idle',
  ACTIVE: 'bg-in-progress-bg text-in-progress',
};

@Component({
  selector: 'orca-status-chip',
  template: `
    <span
      class="inline-flex items-center h-[25px] px-2.5 rounded-status text-xs font-semibold"
      [class]="classes()"
    >
      {{ status() }}
    </span>
  `,
})
export class StatusChip {
  readonly status = input.required<string>();

  protected readonly classes = computed(
    () => STATUS_CLASSES[this.status()] ?? STATUS_CLASSES['IDLE'],
  );
}
