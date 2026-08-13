import { Component, contentChildren, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { OrcaColumn } from './orca-column';

@Component({
  selector: 'orca-data-grid',
  imports: [NgTemplateOutlet],
  template: `
    <div class="overflow-x-auto rounded-card bg-card shadow-card">
      <table class="w-full text-[13px]">
        <thead>
          <tr class="bg-table-header text-left text-ink-table-header">
            @for (col of columns(); track col.key()) {
              <th scope="col" class="px-4 py-2 font-bold">{{ col.header() }}</th>
            }
          </tr>
        </thead>
        <tbody class="text-ink">
          @for (row of rows(); track $index) {
            <tr class="border-b border-row-divider" [class]="rowClass()(row)">
              @for (col of columns(); track col.key()) {
                <td class="px-4 py-2">
                  <ng-container
                    [ngTemplateOutlet]="col.template"
                    [ngTemplateOutletContext]="{ $implicit: row }"
                  />
                </td>
              }
            </tr>
          }
        </tbody>
      </table>

      @if (loading()) {
        <p class="px-4 py-6 text-ink-muted" role="status">Loading…</p>
      } @else if (rows().length === 0) {
        <p class="px-4 py-6 text-ink-muted">{{ emptyMessage() }}</p>
      }
    </div>
  `,
})
export class DataGrid<T> {
  readonly rows = input.required<readonly T[]>();
  readonly loading = input(false);
  readonly emptyMessage = input('Nothing here yet.');
  /** Additive classes for a single row — e.g. the SLA-breach highlight. Defaults to none. */
  readonly rowClass = input<(row: T) => string>(() => '');
  protected readonly columns = contentChildren(OrcaColumn);
}
