import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterOutlet } from '@angular/router';
import { AppShell, ToastHost } from 'ui-registry';
import { runtime } from 'api-client';
import { AuthService } from './auth/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AppShell, ToastHost],
  template: `
    <orca-app-shell
      [userName]="auth.userName()"
      [presence]="presence()"
      (logout)="auth.logout()"
    >
      <router-outlet />
    </orca-app-shell>
    <orca-toast-host />
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
  private readonly presenceApi = inject(runtime.PresenceService);

  private readonly myPresence = rxResource({
    stream: () => this.presenceApi.getMyPresence(),
    defaultValue: undefined,
  });

  protected readonly presence = computed(() =>
    this.myPresence.hasValue() ? this.myPresence.value()?.data?.status : undefined,
  );
}
