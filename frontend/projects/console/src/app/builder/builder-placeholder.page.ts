import { Component } from '@angular/core';
import { PageShell } from 'ui-registry';

/**
 * Reserves the route. Another developer owns everything else under
 * `console/src/app/builder/` — no Foblex, no Monaco, no canvas, no dependency here.
 */
@Component({
  selector: 'app-builder-placeholder-page',
  imports: [PageShell],
  template: `
    <orca-page-shell title="Builder">
      <p class="text-[13px] text-ink-muted">The builder is under development.</p>
    </orca-page-shell>
  `,
})
export class BuilderPlaceholderPage {}
