import { TestBed } from '@angular/core/testing';
import { ToastHost } from './toast-host';
import { ToastService } from './toast.service';

describe('ToastService + ToastHost', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders a shown toast in the host', () => {
    const service = TestBed.inject(ToastService);
    const fixture = TestBed.createComponent(ToastHost);
    fixture.detectChanges();

    service.show('Could not take that work item.', 'error');
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Could not take that work item.');
    expect(el.querySelector('[role="status"]')?.className).toContain('bg-negative-bg');
  });

  it('auto-dismisses a toast after 5 seconds', () => {
    const service = TestBed.inject(ToastService);
    const fixture = TestBed.createComponent(ToastHost);
    fixture.detectChanges();

    service.show('Saved.', 'success');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Saved.');

    vi.advanceTimersByTime(5000);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Saved.');
  });

  it('leaves the page usable — dismiss removes only the targeted toast', () => {
    const service = TestBed.inject(ToastService);
    const fixture = TestBed.createComponent(ToastHost);
    fixture.detectChanges();

    service.show('First');
    service.show('Second');
    fixture.detectChanges();

    expect(service.all().length).toBe(2);
    service.dismiss(service.all()[0].id);

    expect(service.all().length).toBe(1);
    expect(service.all()[0].message).toBe('Second');
  });
});
