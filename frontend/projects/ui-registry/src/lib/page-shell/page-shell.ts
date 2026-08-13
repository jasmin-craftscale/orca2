import { Component, input } from '@angular/core';

@Component({
  selector: 'orca-page-shell',
  template: `
    <div class="p-6">
      <div class="flex items-start justify-between gap-4">
        <div>
          <h1 class="text-[20px] font-bold text-ink-heading">{{ title() }}</h1>
          @if (subtitle()) {
            <p class="mt-1 text-[13px] text-ink-muted">{{ subtitle() }}</p>
          }
        </div>
        <div class="flex items-center gap-2">
          <ng-content select="[slot=actions]" />
        </div>
      </div>

      <div class="mt-6">
        <ng-content />
      </div>
    </div>
  `,
})
export class PageShell {
  readonly title = input.required<string>();
  readonly subtitle = input<string | undefined>(undefined);
}
