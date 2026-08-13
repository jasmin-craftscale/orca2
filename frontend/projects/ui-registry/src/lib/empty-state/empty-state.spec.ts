import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { EmptyState } from './empty-state';

@Component({
  imports: [EmptyState],
  template: `
    <orca-empty-state message="No work items yet.">
      <button type="button">Refresh</button>
    </orca-empty-state>
  `,
})
class HostComponent {}

describe('EmptyState', () => {
  it('renders the message and a projected action', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('No work items yet.');
    expect(el.querySelector('button')?.textContent).toBe('Refresh');
  });
});
