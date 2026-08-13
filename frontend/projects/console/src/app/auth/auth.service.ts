import { computed, inject, Service, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';
import { authConfig } from './auth.config';

@Service()
export class AuthService {
  private readonly oauth = inject(OAuthService);
  private readonly claims = signal<Record<string, unknown> | null>(null);

  readonly isAuthenticated = computed(() => this.claims() !== null);
  readonly userName = computed(() => (this.claims()?.['preferred_username'] as string) ?? '');

  private loginInFlight = false;

  /**
   * Runs once at bootstrap, inside `provideAppInitializer` — the app does not render
   * until this resolves. Redirects to the realm when there is no valid session.
   *
   * On a cold or expired session, `loadDiscoveryDocumentAndLogin()` resolves having
   * done nothing (no `code` param to exchange, no valid stored token) — it does not
   * itself initiate a login. Resolving `bootstrap()` at that point would let the app
   * render with no token: every resource on the page fires its own request, each one
   * 401s independently, and each 401 calls `login()` — four or more concurrent
   * `initCodeFlow()` calls racing their own redirects. Checking here and redirecting
   * before the app initializer resolves closes that race: the app either renders with
   * a definitely-valid token, or never renders at all because the browser is already
   * navigating away. That's also why this deliberately never resolves in that branch.
   */
  async bootstrap(): Promise<void> {
    this.oauth.configure(authConfig);
    await this.oauth.loadDiscoveryDocumentAndLogin();

    if (!this.oauth.hasValidAccessToken()) {
      this.login();
      return new Promise<void>(() => {});
    }

    this.oauth.setupAutomaticSilentRefresh();
    this.claims.set(this.oauth.getIdentityClaims() ?? null);
  }

  /** The token, or null when there is not a valid one — never a stale token. */
  accessToken(): string | null {
    return this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null;
  }

  /**
   * Guarded against re-entrancy: a token can still expire mid-session with several
   * requests in flight at once (the 5s poll fires two together), and each one's 401
   * would otherwise call this independently. Once a redirect is under way the page is
   * leaving anyway, so there is never a reason to reset the flag.
   */
  login(): void {
    if (this.loginInFlight) return;
    this.loginInFlight = true;
    this.oauth.initCodeFlow();
  }

  logout(): void {
    this.oauth.logOut();
  }
}
