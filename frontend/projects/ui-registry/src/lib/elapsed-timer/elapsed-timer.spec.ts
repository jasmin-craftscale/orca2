import { TestBed } from '@angular/core/testing';
import { ElapsedTimer } from './elapsed-timer';

describe('ElapsedTimer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders mm:ss for an elapsed time under an hour', () => {
    const now = Date.now();
    vi.setSystemTime(now);
    const since = new Date(now - 65_000).toISOString();

    const fixture = TestBed.createComponent(ElapsedTimer);
    fixture.componentRef.setInput('since', since);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('01:05');
  });

  it('renders h:mm:ss once elapsed passes an hour', () => {
    const now = Date.now();
    vi.setSystemTime(now);
    const since = new Date(now - (3661 * 1000)).toISOString();

    const fixture = TestBed.createComponent(ElapsedTimer);
    fixture.componentRef.setInput('since', since);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('1:01:01');
  });

  it('ticks every second while running', () => {
    const now = Date.now();
    vi.setSystemTime(now);
    const since = new Date(now).toISOString();

    const fixture = TestBed.createComponent(ElapsedTimer);
    fixture.componentRef.setInput('since', since);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('00:00');

    vi.advanceTimersByTime(3000);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('00:03');
  });

  it('clears its interval on destroy', () => {
    const clearSpy = vi.spyOn(globalThis, 'clearInterval');
    const fixture = TestBed.createComponent(ElapsedTimer);
    fixture.componentRef.setInput('since', new Date().toISOString());
    fixture.detectChanges();

    fixture.destroy();

    expect(clearSpy).toHaveBeenCalled();
  });
});
