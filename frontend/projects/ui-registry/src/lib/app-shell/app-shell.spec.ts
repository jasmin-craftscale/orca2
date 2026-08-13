import { Component } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { AppShell } from './app-shell';

@Component({
  imports: [AppShell],
  template: `
    <orca-app-shell>
      <p>routed page content</p>
    </orca-app-shell>
  `,
})
class HostComponent {}

describe('AppShell', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([])],
    });
  });

  it('renders the wordmark and the three menus', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('ORCA');
    expect(el.textContent).toContain('Operations');
    expect(el.textContent).toContain('Insights');
    expect(el.textContent).toContain('Administration');
  });

  it('renders the signed-in user name', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.componentRef.setInput('userName', 'Demo Clerk');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Demo Clerk');
  });

  it('renders presence as a read-only status chip when provided', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.componentRef.setInput('presence', 'IDLE');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('orca-status-chip')).toBeTruthy();
  });

  it('emits logout when "Log out" is clicked', () => {
    const fixture = TestBed.createComponent(AppShell);
    fixture.componentRef.setInput('userName', 'Demo Clerk');
    fixture.detectChanges();

    const logout = vi.fn();
    fixture.componentInstance.logout.subscribe(logout);

    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el.querySelectorAll('button')[1] as HTMLButtonElement).click();

    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('projects content into the shell body', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('main')?.textContent).toContain(
      'routed page content',
    );
  });
});
