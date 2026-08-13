import { Component, input, output, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { StatusChip } from '../status-chip/status-chip';

/**
 * Presentational only — injects nothing. `App` owns AuthService and the presence
 * resource and feeds both in, so this library stays usable by `kiosk` too.
 *
 * Insights has no routed page yet in WP1 (only `/operations/work-items` and
 * `/administration/builder` exist) — rendered as a disabled item rather than a
 * broken link, per the same rule the plan applies to unrouted menu items.
 */
@Component({
  selector: 'orca-app-shell',
  imports: [RouterLink, RouterLinkActive, StatusChip],
  template: `
    <div class="flex min-h-screen flex-col">
      <header class="sticky top-0 z-40 flex h-14 items-center justify-between bg-card px-6 shadow-header">
        <div class="flex items-center gap-10">
          <span class="text-[18px] font-bold tracking-wide text-navy">ORCA</span>
          <nav class="flex items-center gap-8">
            <a
              routerLink="/operations/work-items"
              routerLinkActive="text-primary"
              class="text-[14px] font-bold text-black hover:text-primary"
            >
              Operations
            </a>
            <span
              class="cursor-not-allowed select-none text-[14px] font-bold text-ink-muted"
              aria-disabled="true"
            >
              Insights
            </span>
            <a
              routerLink="/administration/builder"
              routerLinkActive="text-primary"
              class="text-[14px] font-bold text-black hover:text-primary"
            >
              Administration
            </a>
          </nav>
        </div>

        <div class="flex items-center gap-4">
          @if (presence()) {
            <orca-status-chip [status]="presence()!" />
          }
          <div class="relative">
            <button
              type="button"
              class="text-[13px] font-semibold text-ink hover:text-primary"
              (click)="menuOpen.set(!menuOpen())"
            >
              {{ userName() }}
            </button>
            @if (menuOpen()) {
              <div class="absolute right-0 mt-2 w-40 rounded-base bg-card py-1 shadow-card">
                <button
                  type="button"
                  class="w-full px-4 py-2 text-left text-[13px] text-ink hover:bg-surface-subtle"
                  (click)="onLogout()"
                >
                  Log out
                </button>
              </div>
            }
          </div>
        </div>
      </header>

      <main class="flex-1">
        <ng-content />
      </main>
    </div>
  `,
})
export class AppShell {
  readonly userName = input('');
  readonly presence = input<string | undefined>(undefined);
  readonly logout = output<void>();

  protected readonly menuOpen = signal(false);

  protected onLogout(): void {
    this.menuOpen.set(false);
    this.logout.emit();
  }
}
