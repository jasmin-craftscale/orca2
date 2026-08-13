import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PageShell } from './page-shell';

@Component({
  imports: [PageShell],
  template: `
    <orca-page-shell title="Work items" subtitle="Queued and in progress">
      <button slot="actions" type="button">New</button>
      <p>body content</p>
    </orca-page-shell>
  `,
})
class HostComponent {}

describe('PageShell', () => {
  it('renders the title, subtitle, projected actions and default content', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('h1')?.textContent).toBe('Work items');
    expect(el.textContent).toContain('Queued and in progress');
    expect(el.querySelector('button')?.textContent).toBe('New');
    expect(el.textContent).toContain('body content');
  });
});
