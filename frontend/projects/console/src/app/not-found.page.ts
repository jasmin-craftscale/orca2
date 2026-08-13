import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageShell } from 'ui-registry';

@Component({
  selector: 'app-not-found-page',
  imports: [PageShell, RouterLink],
  template: `
    <orca-page-shell title="Not found">
      <p class="text-[13px] text-ink-muted">That page does not exist.</p>
      <a routerLink="/operations/work-items" class="mt-4 inline-block text-[13px] font-semibold text-primary">
        Back to the work item queue
      </a>
    </orca-page-shell>
  `,
})
export class NotFoundPage {}
