import { Component, input } from '@angular/core';

@Component({
  selector: 'orca-empty-state',
  template: `
    <div class="flex flex-col items-center gap-3 px-4 py-10 text-center text-ink-muted">
      <p class="text-[13px]">{{ message() }}</p>
      <ng-content />
    </div>
  `,
})
export class EmptyState {
  readonly message = input.required<string>();
}
