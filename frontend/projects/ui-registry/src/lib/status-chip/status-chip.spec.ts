import { TestBed } from '@angular/core/testing';
import { StatusChip } from './status-chip';

describe('StatusChip', () => {
  function render(status: string) {
    const fixture = TestBed.createComponent(StatusChip);
    fixture.componentRef.setInput('status', status);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the status text', () => {
    const el = render('QUEUED');
    expect(el.textContent?.trim()).toBe('QUEUED');
  });

  it('maps QUEUED to the queued token pair', () => {
    const el = render('QUEUED');
    const span = el.querySelector('span')!;
    expect(span.className).toContain('bg-queued-bg');
    expect(span.className).toContain('text-queued');
  });

  it('maps IN_PROGRESS to the in-progress token pair', () => {
    const el = render('IN_PROGRESS');
    const span = el.querySelector('span')!;
    expect(span.className).toContain('bg-in-progress-bg');
    expect(span.className).toContain('text-in-progress');
  });

  it('falls back to idle tokens for an unrecognized status', () => {
    const el = render('SOMETHING_UNKNOWN');
    const span = el.querySelector('span')!;
    expect(span.className).toContain('bg-idle-bg');
    expect(span.className).toContain('text-idle');
  });
});
