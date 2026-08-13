import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DataGrid } from './data-grid';
import { OrcaColumn } from './orca-column';

interface Row {
  id: string;
  name: string;
}

@Component({
  imports: [DataGrid, OrcaColumn],
  template: `
    <orca-data-grid [rows]="rows" [loading]="loading" [rowClass]="rowClass" emptyMessage="No rows yet.">
      <ng-template orcaColumn="id" header="ID" let-row>{{ row.id }}</ng-template>
      <ng-template orcaColumn="name" header="Name" let-row>{{ row.name }}</ng-template>
    </orca-data-grid>
  `,
})
class HostComponent {
  rows: readonly Row[] = [];
  loading = false;
  rowClass = (row: Row) => (row.id === '2' ? 'bg-negative-row border-negative-edge' : '');
}

describe('DataGrid', () => {
  function setup() {
    const fixture = TestBed.createComponent(HostComponent);
    return fixture;
  }

  it('renders a header per column and no data rows when empty', () => {
    const fixture = setup();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelectorAll('thead th').length).toBe(2);
    expect(el.querySelectorAll('tbody tr').length).toBe(0);
    expect(el.textContent).toContain('No rows yet.');
  });

  it('renders one row', () => {
    const fixture = setup();
    fixture.componentInstance.rows = [{ id: '1', name: 'Alpha' }];
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelectorAll('tbody tr').length).toBe(1);
    expect(el.textContent).toContain('Alpha');
    expect(el.textContent).not.toContain('No rows yet.');
  });

  it('renders many rows', () => {
    const fixture = setup();
    fixture.componentInstance.rows = [
      { id: '1', name: 'Alpha' },
      { id: '2', name: 'Bravo' },
      { id: '3', name: 'Charlie' },
    ];
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelectorAll('tbody tr').length).toBe(3);
  });

  it('shows the empty state only when not loading', () => {
    const fixture = setup();
    fixture.componentInstance.loading = true;
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('Loading…');
    expect(el.textContent).not.toContain('No rows yet.');
  });

  it('applies rowClass on top of the base row classes', () => {
    const fixture = setup();
    fixture.componentInstance.rows = [
      { id: '1', name: 'Alpha' },
      { id: '2', name: 'Bravo' },
    ];
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const rows = el.querySelectorAll('tbody tr');

    expect(rows[0].className).toContain('border-row-divider');
    expect(rows[0].className).not.toContain('bg-negative-row');
    expect(rows[1].className).toContain('border-row-divider');
    expect(rows[1].className).toContain('bg-negative-row');
    expect(rows[1].className).toContain('border-negative-edge');
  });
});
