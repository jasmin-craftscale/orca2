# WP1 + a thin slice of WP3 — execution plan (autonomous agent)

**Written by the frontend orchestrator, 13 August 2026, for an autonomous agent with
full local access.** Every command in §2 was executed against this machine while this
document was written, and every API signature in it was read out of the installed
packages rather than remembered. Where this document gives code, the code was compiled
against Angular 22.1.1 as installed in this workspace.

---

## Orientation — read this first if you have just started

**Where you are.** The working directory is a workspace root holding two sibling
repositories: **`orca/`** (ORCA 2.0 — a Java 25 / Spring Boot 4 backend plus the Angular
frontend in `orca/frontend/`) and **`Lynxis-Gate/`** (ORCA 1.x, the system being replaced
— **read-only**, and you almost certainly do not need it for this work package).

**Every path and command in this document is relative to the `orca/` repository root.**
If your shell starts at the workspace root, `cd orca` once, now, and stay there except
where a step says otherwise.

**What ORCA is, in two sentences.** It is gate automation for logistics facilities:
cameras read truck plates, customer-designed processes drive barriers, and work the
automation cannot finish is routed to a human clerk. You are building the console that
clerk, their supervisor and the administrator use — nothing can be demonstrated to a
customer without it.

**What you are building here.** The walking skeleton: Keycloak login → an HTTP
interceptor that attaches the token to `/api/**` and nothing else → the app shell → the
work-item queue and its completion form, reading and writing **live data through the
generated typed client**. 52 API paths are already contracted *and* implemented. Build
against them; do not mock what exists.

**Read before you start, in this order** (all under `orca/docs/` unless said otherwise):

1. **This document**, in full.
2. `console-implementation-plan.md` **§2** (what already exists) and **§4** (the traps).
3. `frontend-from-1x.md` **§2** (the visual language — the tokens you style with) and
   **§3** (the eight 1.x defects deliberately not carried forward).
4. `orca/frontend/AGENTS.md` (Angular conventions — binding, with one exception: its
   line "Use the async pipe to handle observables" is wrong here and §3.4 has you delete
   it).

This document **overrides those** wherever they disagree, because it corrects errors
they contain.

---

## 0 · The rules you work under

1. **If anything here is wrong, missing, or contradicted by the code: stop and report
   it. Do not work around it.** A gap reported is worth more than a gap filled. Every
   defect found in the last agent-executed plan in this programme surfaced because the
   agent stopped instead of improvising.
2. **You never edit an OpenAPI contract** (`services/**/openapi/*.yaml`). You generate a
   typed client from it. A missing field or endpoint is a written finding handed up, not
   a hand-written HTTP call and not a mock. See `ORCHESTRATOR_BOUNDARIES.md` §2.
3. **Verify by executing, never by asserting.** Drive the UI in a real browser, then
   confirm the resulting state in the database.
4. **Do not commit, push or merge** unless the technical lead has explicitly said so in
   the prompt that launched you. §9 defines the commit boundaries for when they do.
5. **Never write to the ORCA 1.x database** (port 11433, `OrcaCommercial`). Read-only.
6. **Out of scope, do not build:** the workflow builder and screen builder (another
   developer owns `console/src/app/builder/**` — you create the placeholder route and
   nothing inside it), the kiosk app, i18n, Excel export, WebSocket live updates, the
   data-driven screen renderer, grid column preferences.

## 1 · The seven decisions already made for you

Do not re-open these. They exist so you never have to ask.

| # | Decision | What you do | Why |
|---|---|---|---|
| 1 | **Auth library is `angular-oauth2-oidc@22`** | `npm i angular-oauth2-oidc@22` | The backend validates any OIDC issuer by signature and knows nothing about Keycloak; the console should match. One dependency, not two. (`keycloak-angular` also works — if the technical lead's prompt says to use it instead, that overrides this row and only §4 changes.) |
| 2 | **Read primitive is `rxResource`, never `httpResource`** | §7.2 | The generated services return `Observable<T>` and build their own URL from an injected `BASE_PATH`. `httpResource` takes a URL, so using it means hand-writing URLs — which rule 2 forbids. `frontend-console-plan.md` §5.1 is wrong on this row. |
| 3 | **Commands use `firstValueFrom`, then `.reload()` the resource** | §7.3 | A take/complete is one shot. Nothing to subscribe to over time. |
| 4 | **`tsconfig.json` paths point at library *source*, not `dist/`** | §3.1 | As shipped they point at `./dist/*`, which is gitignored, so the console cannot compile until both libraries are built and `ng serve` never picks up a `ui-registry` edit. This deletes the problem instead of documenting it. |
| 5 | **The proxy is wired into `angular.json`** | §3.2 | It is prose-only today, and both `launch.json` files omit it. Forgetting it does **not** produce a clean 404 — the dev server's SPA fallback answers `/api/v1/…` with **200 and the app's own `index.html`**, so the typed client dies parsing HTML as JSON. Verified both ways on 13 Aug 2026. |
| 6 | **Layers are: component → one thin feature service → generated client** | §7.1 | Three layers, not five. No facade, no repository, no state-management library. |
| 7 | **Native `<dialog>` for modals and confirms** | §6.6 | Focus trap, `Escape` and the backdrop are free and accessible; no dependency. |

**The app is zoneless** (no `zone.js` installed, no `polyfills` entry). All state that a
template reads must be a signal. A `setInterval` that sets a signal is fine; one that
mutates a plain field renders nothing.

## 2 · Preflight — nine gates, in order

Run from the `orca/` repository root. Where a gate `cd`s somewhere, **return to the
repository root before the next one.** **If a gate does not produce what it says, stop
and report.** Do not proceed on a red gate; every downstream step assumes these.

### Gate 1 — branch

```bash
git -C . fetch --all --prune && git rev-parse --abbrev-ref HEAD
```

Then `git checkout develop && git pull && git checkout -b feature/console-wp1`.
Run `git rev-parse --abbrev-ref HEAD` in the **same command** as every `git add` — a
second orchestrator session moves this repository under you.

### Gate 2 — the stack is up and nobody else is using it

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep orca-
```

Expect `orca-sqlserver`, `orca-keycloak`, `orca-tos-stub`, `orca-device-host-stub`, all
healthy. If they are not running: `cd deploy && docker compose up -d && docker compose run --rm bootstrap`.

⚠️ `deploy/.env` is machine-local and already exists: **SQL Server 21433, Keycloak
18080.** Do not overwrite it with `.env.example`.

⚠️ **A backend orchestrator session shares this stack.** A running service poisons its
integration suite and vice versa. If ports 18081–18083 are already listening when you
have not started them, **stop and report** — someone else is driving.

### Gate 3 — the three services

⚠️ **`./gradlew bootRun` never returns.** Start each of the three in the **background**
and keep them running for the rest of this work package — if you run one in the
foreground you will hang there until you are killed. Send each one's output to a log file
you can read when something fails:

```bash
mkdir -p /tmp/orca-wp1
```

then start each with its output redirected (`> /tmp/orca-wp1/<service>.log 2>&1 &`, or
your harness's background mechanism). **Do not** try to run all three in one command that
waits on them.

The exports are not optional — a service started without them points at ports nothing
listens on. Export them in the same shell as each `bootRun`:

```bash
export ORCA_DB_URL='jdbc:sqlserver://localhost:21433;databaseName=orca;encrypt=true;trustServerCertificate=true'
export ORCA_OIDC_ISSUER_URI='http://localhost:18080/realms/orca'
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local --server.port=18081' > /tmp/orca-wp1/core.log 2>&1 &
```

```bash
export ORCA_DB_URL='jdbc:sqlserver://localhost:21433;databaseName=orca;encrypt=true;trustServerCertificate=true'
export ORCA_OIDC_ISSUER_URI='http://localhost:18080/realms/orca'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local --server.port=18082 --orca.runtime.edge-base-url=http://localhost:18083' > /tmp/orca-wp1/runtime.log 2>&1 &
```

```bash
export ORCA_DB_URL='jdbc:sqlserver://localhost:21433;databaseName=orca;encrypt=true;trustServerCertificate=true'
export ORCA_OIDC_ISSUER_URI='http://localhost:18080/realms/orca'
./gradlew bootRun -p services/orca-edge --args='--spring.profiles.active=local --server.port=18083 --orca.edge.runtime-base-url=http://localhost:18082' > /tmp/orca-wp1/edge.log 2>&1 &
```

They take a minute or so to come up, and a Gradle build runs first. **Poll — do not assume:**

```bash
for i in $(seq 1 60); do
  ok=0
  for p in 18081 18082 18083; do
    [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$p/actuator/health)" = "200" ] && ok=$((ok+1))
  done
  echo "healthy: $ok/3"; [ $ok -eq 3 ] && break; sleep 5
done
```

Expect `healthy: 3/3`. If a service never comes up, read its log in `/tmp/orca-wp1/`.

⚠️ **A green build does not mean a service starts** — every backend suite constructs its
beans directly rather than refreshing a context, so a broken bean definition passes all
of them. That is how a previous phase shipped an `orca-edge` that could not boot. The
health check above is the real evidence.

### Gate 4 — demo data

```bash
cd deploy && ./demo/seed.sh
```

One site, area, lane, camera, barrier, the `tos` connector, and the clerk world
(`usr-demo-clerk`, team, screen `scr-demo-manual`, routing rule).

### Gate 5 — the identity link, and proof that it took

**This was stale on 13 Aug 2026 and is the single most likely reason your first API call
fails with 403 `USER_NOT_LINKED`.** The realm authenticates people; the platform resolves
the token's `sub` through core's operator directory. Re-link, then *verify the two
values match* — do not assume the `UPDATE` worked:

```bash
SUB=$(curl -s -X POST http://localhost:18080/realms/orca/protocol/openid-connect/token -d 'grant_type=password&client_id=orca-console&username=clerk&password=clerk&scope=openid' | python3 -c 'import sys,json,base64; t=json.load(sys.stdin)["access_token"]; p=t.split(".")[1]; p+="="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))["sub"])')
docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -Q "UPDATE core.user_account SET keycloak_subject = '$SUB' WHERE external_id = 'usr-demo-clerk'"
echo "token sub : $SUB"
docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -h -1 -W -Q "SET NOCOUNT ON; SELECT keycloak_subject FROM core.user_account WHERE external_id='usr-demo-clerk'"
```

The last two lines must print **the same GUID**. If they do not, stop and report.

⚠️ `-I` is not optional in that `sqlcmd` invocation — without it, writes to tables with
filtered indexes fail with a message that names SET options and no table.

### Gate 6 — the frontend installs and every project builds

**From here to the end of the work package you work in `orca/frontend/`.** Gates 6–8 and
every step from §3 onwards assume that directory; only §8's database and demo commands
go back to the repository root.

```bash
cd frontend && npm ci
```

⚠️ **`npx ng build` on its own FAILS** — `Error: Cannot determine project for command.`
So does `npm run build`. This is a multi-project workspace; name each project, and in
this order:

```bash
npx ng build api-client && npx ng build ui-registry && npx ng build console && npx ng build kiosk
```

Four successes. (After §3.1 the order stops mattering, but use it until then.)

### Gate 7 — the test suites

```bash
npx ng test console --watch=false && npx ng test kiosk --watch=false && npx ng test ui-registry --watch=false
```

Expect **2 passed**, **2 passed**, **1 passed**. ⚠️ Tests run under **Vitest**, not Karma
— plain `--watch=false` works, `--browsers` demands an extra package. ⚠️ Do not run
`ng test api-client`: it has no specs and exits 1 on a green tree.

### Gate 8 — the contracts and the generated client agree

```bash
npm run api:generate && git status --porcelain -- projects/api-client/src/lib
```

Empty output means the checked-in client matches the contracts.

⚠️ **`npm run api:check` cannot currently detect drift** — its `git diff --exit-code`
only sees tracked files, and `frontend/` is untracked in git today. Use
`git status --porcelain` as above until the workspace is committed. If it reports
changes, the backend changed a contract: **regenerate, absorb the break, and report it**.
A contract change is meant to break this build — that is the mechanism working.

### Gate 9 — auth answers before you write any auth code

```bash
curl -s http://localhost:18080/realms/orca/.well-known/openid-configuration | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["issuer"]); print(d["authorization_endpoint"]); print(d["end_session_endpoint"])'
```

Expect all three under `http://localhost:18080/realms/orca`. The realm already contains
the public client `orca-console` (PKCE `S256`, redirect `http://localhost:4200/*`) and
the user `clerk`/`clerk`, both persisted in `deploy/keycloak/realm-export.json`. **You do
not need to change the realm.** Logout was verified to redirect back to
`http://localhost:4200/` with no extra client attribute.

---

## 3 · Step 1 — workspace repairs

These four repairs remove defects that would otherwise cost you or a later agent hours.

### 3.1 · Point the path mappings at library source

In `frontend/tsconfig.json`, replace the `paths` block:

```json
    "paths": {
      "api-client": ["./projects/api-client/src/public-api.ts"],
      "ui-registry": ["./projects/ui-registry/src/public-api.ts"]
    }
```

Verify it worked — this must now pass **with no `dist/` present**:

```bash
rm -rf dist && npx ng build console && npx ng test console --watch=false
```

This exact change was executed on 13 Aug 2026 against a console component importing from
**both** libraries with `dist/` deleted: the build and the 2 tests passed, and
`ng build api-client`, `ng build ui-registry` and `ng build kiosk` were unaffected. If it
fails for you, something else moved — stop and report.

### 3.2 · Wire the proxy into the build, and harden its targets

In `angular.json`, under `projects.console.architect.serve.configurations.development`,
add the proxy so `ng serve console` is sufficient on its own:

```json
            "development": {
              "buildTarget": "console:build:development",
              "proxyConfig": "proxy.config.json"
            }
```

That exact placement was executed on 13 Aug 2026 — the schema accepts `proxyConfig`
inside `configurations.development`, and `serve` already defaults to that configuration.

In `proxy.config.json`, change every `localhost` to `127.0.0.1`. On this machine
`localhost` resolves to `::1` first and the services' forwarded ports answer on IPv4 —
`127.0.0.1` is immune to that whole class of `ECONNREFUSED`.

Add the same `--proxy-config proxy.config.json` argument to `.claude/launch.json` and
`frontend/.claude/launch.json`, both of which omit it today.

**Verify the proxy is actually engaged** — with `ng serve console` running and the
backend services up:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4200/api/v1/work-items
```

`401` or `200` means the proxy is forwarding. **`200` served from the SPA fallback looks
identical in the status line**, so if you are unsure, stop the services and re-run: a
wired proxy answers **502**, an unwired one answers **200 with `index.html`**. Check the
body, not just the code.

⚠️ Probe the dev server as `localhost`, not `127.0.0.1` — it binds IPv6-first here, and
`127.0.0.1:4200` returns nothing at all.

### 3.3 · Delete the scaffold page

```bash
rm -rf projects/console/src/app/theme-check
```

and remove its route from `app.routes.ts`. It proved the token pipeline renders; it is
not a product screen.

### 3.4 · Make one instruction file authoritative instead of four copies

`frontend/AGENTS.md`, `frontend/.claude/CLAUDE.md`,
`frontend/.github/copilot-instructions.md` and `frontend/.cursor/rules/cursor.mdc` are
byte-identical stock Angular CLI guidance. Two divergent copies of one rule is how the
last corpus rotted, and this is four.

Make `frontend/AGENTS.md` the single source: keep the existing Angular/TypeScript rules,
**delete the line "Use the async pipe to handle observables"** (it contradicts decision 2
and §7.2), and append an ORCA section carrying the seven decisions in §1, the
`ng build <project>` gotcha, and the "never hand-write an HTTP call" rule.

Then make `frontend/.claude/CLAUDE.md` import it rather than copy it — a single line
holding `@AGENTS.md` (bare, no backticks, in that file only), exactly as `orca/CLAUDE.md`
does at the root.

⚠️ **Leave `.github/copilot-instructions.md` and `.cursor/rules/cursor.mdc` alone and
report them.** Neither Copilot nor Cursor resolves the `@`-import that makes this work
for Claude Code, so the choice is a stale copy or no rules at all for whoever uses those
tools — and stream 5's named developer works in this repository too. That is the
technical lead's call, not yours. Say so in your report and move on.

**Gate:** `npx ng build console && npx ng build kiosk && npx ng test console --watch=false`
— green, with `dist/` deleted.

---

## 4 · Step 2 — authentication

### 4.1 · Install

```bash
npm i angular-oauth2-oidc@22
```

### 4.2 · `projects/console/src/app/auth/auth.config.ts`

```ts
import { AuthConfig } from 'angular-oauth2-oidc';

/**
 * The realm authenticates PEOPLE. Services validate the resulting token locally by
 * signature and never call the issuer on the gate path, so nothing here is on a
 * truck's critical path.
 *
 * `requireHttps` is deliberately left at its default of 'remoteOnly': it permits
 * http for localhost and refuses it for every other host, so the dev issuer works
 * and a plaintext production issuer still cannot be configured by accident.
 */
export const authConfig: AuthConfig = {
  issuer: 'http://localhost:18080/realms/orca',
  clientId: 'orca-console',
  redirectUri: window.location.origin + '/',
  postLogoutRedirectUri: window.location.origin + '/',
  responseType: 'code',
  scope: 'openid profile email',
  showDebugInformation: false,
};
```

⚠️ The issuer is hard-coded because the console is served same-origin behind a reverse
proxy in production and the deployment story for this value is not settled. **Do not
invent an environment-file scheme** — note it in your report as a decision for the
technical lead.

### 4.3 · `projects/console/src/app/auth/auth.service.ts`

```ts
import { computed, inject, Service, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';
import { authConfig } from './auth.config';

@Service()
export class AuthService {
  private readonly oauth = inject(OAuthService);
  private readonly claims = signal<Record<string, unknown> | null>(null);

  readonly isAuthenticated = computed(() => this.claims() !== null);
  readonly userName = computed(() => (this.claims()?.['preferred_username'] as string) ?? '');

  /** Runs once at bootstrap. Redirects to the realm when there is no valid session. */
  async bootstrap(): Promise<void> {
    this.oauth.configure(authConfig);
    await this.oauth.loadDiscoveryDocumentAndLogin();
    this.oauth.setupAutomaticSilentRefresh();
    this.claims.set(this.oauth.getIdentityClaims() ?? null);
  }

  /** The token, or null when there is not a valid one — never a stale token. */
  accessToken(): string | null {
    return this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null;
  }

  login(): void {
    this.oauth.initCodeFlow();
  }

  logout(): void {
    this.oauth.logOut();
  }
}
```

`@Service()` is the Angular 22 replacement for `@Injectable({ providedIn: 'root' })` and
is verified present in 22.1.1.

### 4.4 · `projects/console/src/app/auth/api-token.interceptor.ts`

```ts
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Attaches the bearer token to ORCA's own API and to NOTHING ELSE.
 *
 * The generated clients are configured with an empty basePath, so every call they
 * make is the relative path `/api/v1/...`. The realm's own endpoints are absolute
 * URLs on another origin and must never receive this header — sending a platform
 * token to the identity provider would leak it outside the API it was issued for.
 *
 * ⚠️ THE ORDER OF THE NEXT TWO STATEMENTS IS LOAD-BEARING. The URL check comes
 * FIRST and `inject(AuthService)` second, deliberately. At startup AuthService is
 * being constructed when it asks OAuthService to fetch the discovery document;
 * that request passes through this interceptor, and injecting AuthService before
 * the early return would ask for the very service that is still constructing —
 * a cyclic dependency (NG0200) that kills the app before it renders. Because the
 * realm's URLs are absolute, they return early and never reach the inject.
 * Do not "tidy" this by hoisting the inject to the top of the function.
 */
export const apiTokenInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }

  const auth = inject(AuthService);
  const token = auth.accessToken();
  const authorized = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        auth.login();
      }
      return throwError(() => error);
    }),
  );
};
```

### 4.5 · `api-token.interceptor.spec.ts` — required, three cases

Write it with `TestBed` + `provideHttpClient(withInterceptors([apiTokenInterceptor]))` +
`provideHttpClientTesting()`, stubbing `AuthService` with `{ accessToken: () => 'tkn', login: () => {} }`:

1. A request to `/api/v1/work-items` **carries** `Authorization: Bearer tkn`.
2. A request to `http://localhost:18080/realms/orca/protocol/openid-connect/token`
   carries **no** `Authorization` header. *(This is the test that matters — it is the
   one that fails if someone later "simplifies" the URL check.)*
3. A `401` on an `/api/**` request calls `AuthService.login()` once.

### 4.6 · `projects/console/src/app/app.config.ts`

```ts
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideOAuthClient } from 'angular-oauth2-oidc';
import { core, runtime } from 'api-client';

import { apiTokenInterceptor } from './auth/api-token.interceptor';
import { AuthService } from './auth/auth.service';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([apiTokenInterceptor])),
    provideOAuthClient(),

    // Same origin: the dev server proxies, the reverse proxy does it in production.
    // Both contracts must be wired — each namespace has its own BASE_PATH token, and
    // an unwired one silently falls back to the OpenAPI document's localhost:808x
    // server URL, which bypasses the proxy and dies on CORS that does not exist.
    runtime.provideApi(''),
    core.provideApi(''),

    provideAppInitializer(() => inject(AuthService).bootstrap()),
  ],
};
```

`withComponentInputBinding()` is what lets the detail page take its `:id` as a signal
`input()` instead of reading the router.

**Gate:** `npx ng serve console`, open `http://localhost:4200`, and you are redirected to
Keycloak. Log in `clerk`/`clerk` and you land back on the app. Then in DevTools →
Network, confirm an `/api/**` request carries the `Authorization` header and that no
request to `:18080` does.

---

## 5 · Step 3 — routes, the shell host and the builder placeholder

`App` is where the library's shell meets the application's services.

⚠️ `App` ships with `templateUrl: './app.html'` and `styleUrl: './app.css'`. Moving to an
inline template means **deleting `app.html` and `app.css` and removing both properties** —
declaring `template` alongside `templateUrl` is a compile error, not a preference.

```ts
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AppShell],
  template: `
    <orca-app-shell
      [userName]="auth.userName()"
      [presence]="presence()"
      (logout)="auth.logout()"
    >
      <router-outlet />
    </orca-app-shell>
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
```

⚠️ **This breaks `app.spec.ts` and you must fix it in the same step.** The existing spec
does `TestBed.configureTestingModule({ imports: [App] })` with no providers; once `App`
injects `AuthService` — which injects `OAuthService`, which needs `HttpClient` — it fails
to construct. Give the spec `provideHttpClient()`, `provideHttpClientTesting()`,
`provideRouter([])` and a stub `AuthService`, or override the provider. Its two existing
assertions (the component constructs, and a `router-outlet` renders) must still pass —
the outlet is now inside the shell, which does not change what `querySelector` finds.

`app.routes.ts`. **Every feature route is lazy.**

```ts
import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'operations/work-items' },
  {
    path: 'operations/work-items',
    loadComponent: () => import('./operations/work-items/work-item-queue.page').then((m) => m.WorkItemQueuePage),
  },
  {
    path: 'operations/work-items/:id',
    loadComponent: () => import('./operations/work-items/work-item-detail.page').then((m) => m.WorkItemDetailPage),
  },
  {
    path: 'administration/builder',
    loadComponent: () => import('./builder/builder-placeholder.page').then((m) => m.BuilderPlaceholderPage),
  },
  {
    path: '**',
    loadComponent: () => import('./not-found.page').then((m) => m.NotFoundPage),
  },
];
```

`not-found.page.ts` is a `PageShell` with "That page does not exist." and a link back to
the queue. **There is no separate error page** — WP1's "error route" is served by the
toast path in §6.7: an API failure becomes a toast and leaves the page usable, which is
the whole of the error story until WP6.

`builder/builder-placeholder.page.ts` renders one line saying the builder is under
development. ⚠️ **Nothing else goes in `console/src/app/builder/`** — no Foblex, no
Monaco, no canvas, no dependency. Another developer owns that folder; you are reserving
the slot.

The remaining menu items in §5 of `console-implementation-plan.md` are WP4–WP6 and are
**not** routed yet. The shell renders them as disabled items, not as broken links.

---

## 6 · Step 4 — the components, in `ui-registry`

Build **only these eight**, styled exclusively with the theme tokens from
`styles/orca-theme.css` — **never a raw hex**. Export each from
`projects/ui-registry/src/public-api.ts`. **Each one needs a unit test.**

Delete the scaffold placeholder — **both files together**, or the suite breaks on a
missing import:

```bash
rm projects/ui-registry/src/lib/ui-registry.ts projects/ui-registry/src/lib/ui-registry.spec.ts
```

and remove its `export * from './lib/ui-registry';` line from `public-api.ts`. Gate 7's
"ui-registry: 1 passed" describes the workspace *before* this step; afterwards the count
is however many tests your eight components carry.

### 6.1 · `AppShell` (`orca-app-shell`)

Sticky white topbar, ~56px, `bg-card shadow-header`. Left: the ORCA wordmark in
`text-navy`. Centre: three menus — **Operations · Insights · Administration** — 14px
bold, `text-black`, active/hover `text-primary`.

⚠️ Active state comes from **`routerLinkActive`**, never a manual `module` input. 1.x
passes the active module down by hand and that is a defect not carried forward.

Right: a presence indicator and a user menu button showing the signed-in user, with a
**Log out** item. Content projects through `<ng-content />`.

**The shell is presentational and injects nothing.** Its whole surface is:

```ts
readonly userName = input('');
readonly presence = input<string | undefined>(undefined);
readonly logout = output<void>();
```

`presence` renders as a read-only `StatusChip`. Clicking it is **not** in scope — setting
presence is WP3 proper. `console`'s `App` (§5) owns `AuthService` and the presence
resource and feeds both in.

⚠️ **Never import anything from `console` into `ui-registry`.** The library is the
dependency, not the dependent; reaching for `AuthService` from inside it inverts that and
makes the library unusable by `kiosk`.

⚠️ `routerLinkActive` and `routerLink` come from `@angular/router`, which is **not**
currently in `projects/ui-registry/package.json`'s `peerDependencies` (it lists only
`@angular/common` and `@angular/core`). Add it there when you add the shell.

### 6.2 · `PageShell` (`orca-page-shell`)

`title` input, an optional `subtitle`, an actions slot
(`<ng-content select="[slot=actions]" />`) and default content projection. Nothing else.

### 6.3 · `DataGrid` (`orca-data-grid`) — the highest-leverage component

Columns are declared by the consumer as one `ng-template` per column, so the grid never
knows a domain type.

```ts
import { Directive, TemplateRef, inject, input } from '@angular/core';

@Directive({ selector: 'ng-template[orcaColumn]' })
export class OrcaColumn {
  readonly key = input.required<string>({ alias: 'orcaColumn' });
  readonly header = input('');
  readonly template = inject(TemplateRef);
}
```

```ts
import { Component, contentChildren, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { OrcaColumn } from './orca-column';

@Component({
  selector: 'orca-data-grid',
  imports: [NgTemplateOutlet],
  template: `
    <div class="overflow-x-auto rounded-card bg-card shadow-card">
      <table class="w-full text-[13px]">
        <thead>
          <tr class="bg-table-header text-left text-ink-table-header">
            @for (col of columns(); track col.key()) {
              <th scope="col" class="px-4 py-2 font-bold">{{ col.header() }}</th>
            }
          </tr>
        </thead>
        <tbody class="text-ink">
          @for (row of rows(); track $index) {
            <tr class="border-b border-row-divider">
              @for (col of columns(); track col.key()) {
                <td class="px-4 py-2">
                  <ng-container
                    [ngTemplateOutlet]="col.template"
                    [ngTemplateOutletContext]="{ $implicit: row }"
                  />
                </td>
              }
            </tr>
          }
        </tbody>
      </table>

      @if (loading()) {
        <p class="px-4 py-6 text-ink-muted" role="status">Loading…</p>
      } @else if (rows().length === 0) {
        <p class="px-4 py-6 text-ink-muted">{{ emptyMessage() }}</p>
      }
    </div>
  `,
})
export class DataGrid<T> {
  readonly rows = input.required<readonly T[]>();
  readonly loading = input(false);
  readonly emptyMessage = input('Nothing here yet.');
  protected readonly columns = contentChildren(OrcaColumn);
}
```

⚠️ Loading is **per-region** — never 1.x's full-screen blocking overlay.
**Test:** renders 0, 1 and many rows correctly, and shows the empty state only when not
loading.

### 6.4 · `StatusChip` (`orca-status-chip`)

`status` input; maps to token pairs — `QUEUED` → `bg-queued-bg text-queued`,
`IN_PROGRESS` → `bg-in-progress-bg text-in-progress`, `COMPLETED` →
`bg-positive-bg text-positive-text`, `FAILED` → `bg-negative-bg text-negative`. Same
mapping serves `PresenceStatus` (`IDLE` → idle tokens, `WORKING` → in-progress,
`BREAK` → neutral, `DND`/`OFFLINE` → idle). ~25px tall, `rounded-status`, 12px semibold.

### 6.5 · `ElapsedTimer` (`orca-elapsed-timer`)

`since` input (ISO string). Renders `mm:ss`, or `h:mm:ss` past an hour. Ticks with
`setInterval(…, 1000)` writing a signal, cleared in `ngOnDestroy` — the app is zoneless,
so a signal is what makes it repaint.

### 6.6 · `ConfirmDialog` (`orca-confirm-dialog`)

⚠️ **Separate from `Toast`.** 1.x conflates notification and confirmation into one
`Toaster` with eight status modes imported by 85 files; it is the single worst component
in the old console and it is not carried forward.

Native `<dialog>` opened with `showModal()`. Inputs `title`, `message`, `confirmLabel`;
outputs `confirmed`, `cancelled`. `rounded-modal`. Focus lands on the confirm button.

### 6.7 · `ToastService` + `ToastHost`

A root service holding `signal<Toast[]>` with `show(message, kind)`; the host renders
them top-right and auto-dismisses after 5s. One error path: an API error becomes a toast
and **leaves the page usable**.

### 6.8 · `EmptyState` (`orca-empty-state`)

`message` input, optional projected action. Used by the grid's empty slot.

---

## 7 · Step 5 — the work-item queue and detail

### 7.1 · The feature service — `operations/work-items/work-items.service.ts`

One thin service. It wraps the generated client and **nothing else** — no caching, no
store, no mapping layer.

```ts
import { Service, inject } from '@angular/core';
import { runtime } from 'api-client';
import { firstValueFrom } from 'rxjs';

@Service()
export class WorkItemsApi {
  private readonly api = inject(runtime.WorkItemsService);

  list(status: 'QUEUED' | 'IN_PROGRESS') {
    return this.api.listWorkItems(status);
  }

  take(id: string) {
    return firstValueFrom(this.api.takeWorkItem(id));
  }

  takeover(id: string) {
    return firstValueFrom(this.api.takeoverWorkItem(id));
  }

  park(id: string) {
    return firstValueFrom(this.api.parkWorkItem(id));
  }

  assign(id: string, assigneeExternalId: string) {
    return firstValueFrom(this.api.assignWorkItem(id, { assigneeExternalId }));
  }

  complete(id: string, correctedEventData: string) {
    return firstValueFrom(this.api.completeWorkItem(id, { correctedEventData }));
  }
}
```

### 7.2 · Reading — the `rxResource` pattern, verified against Angular 22.1.1

The option names are `params` and `stream`.

⚠️ **`value()` throws in the error state, and a `defaultValue` does NOT prevent that.**
Read the implementation if you doubt it: `defaultValue` covers *not-yet-loaded* and
*reloading-after-error*; in the error state itself `value()` raises `ResourceValueError`.
An unguarded `value()` in a template therefore throws **during render** the first time an
API call fails, which looks like a mysterious crash rather than a failed request.

**`hasValue()` is the guard** — it short-circuits on the error state before touching
`value()`. Always go through a `computed` that uses it:

```ts
private readonly api = inject(WorkItemsApi);

protected readonly queued = rxResource({
  stream: () => this.api.list('QUEUED'),
  defaultValue: undefined,
});

// hasValue() returns false in the error state without reading value(), so this
// computed is safe to read from the template on every path.
protected readonly queuedRows = computed(() =>
  this.queued.hasValue() ? (this.queued.value()?.data ?? []) : [],
);
```

Surface the failure itself from `error()` — a toast (§6.7), with the page still usable.

`ResourceRef` gives you `value()`, `hasValue()`, `isLoading()`, `error()`, `status()`
(`idle | loading | reloading | resolved | error | local`) and `reload()`. Feed a
`signal()` into `params: () => …` when a filter should re-fetch by construction.

### 7.3 · Commands

```ts
protected async take(id: string): Promise<void> {
  try {
    await this.api.take(id);
    this.queued.reload();
    this.inProgress.reload();
    await this.router.navigate(['/operations/work-items', id]);
  } catch (error) {
    this.toast.show('Could not take that work item.', 'error');
  }
}
```

Every action goes through `ConfirmDialog` first, and every action reloads the resources
it affected.

### 7.4 · The queue page — `/operations/work-items`

Two `DataGrid`s under one `PageShell`: **Queued** and **In progress**. 1.x splits them
and operators expect that; keep it.

Columns: work item · lane · visit · elapsed (`ElapsedTimer` on `queuedAt` for the first
grid, `startedAt` for the second) · `StatusChip` · actions.

⚠️ **Two gaps found while writing this plan. Neither is a reason to stop — build what is
there, and put both in your report.**

- **`WorkItem` carries no plate.** The fields are `externalId`, `visitExternalId`,
  `laneExternalId`, `processDefinitionKey`, `nodeReference`, `screenExternalId`,
  `status`, `assignee`, `queuedAt`, `startedAt`, `completedAt`,
  `completionDurationSec`, `slaBreachedAt`, `eventData`, `correctedEventData` — and the
  `work_item` table has no plate column either. 1.x's queue shows the plate, because that
  is how an operator identifies the truck at the barrier. **Show `visitExternalId`.** Do
  **not** fetch each visit to resolve a plate — that is an N+1 on the operator's hottest
  screen. Look at a real `eventData` payload once the first item exists, say in your
  report whether the plate is in it, and record "the work-item list should carry the
  plate" as a contract request for the technical lead.
- **`listAssignableOperators()` returns `PresenceActivity[]`**, which is
  `userExternalId`, `status`, `startedAt`, `endedAt` — **no display name**. The Assign
  dialog will list raw external ids. Build it that way and report that a display name is
  needed. Do not cross-call core's user directory to decorate it; that is a second
  request per row and a decision that is not yours.

Actions: **Take** (queued) · **Take over** (in progress) · **Park** · **Assign** (choose
from `runtime.PresenceService.listAssignableOperators()`).

Refresh: `setInterval` calling `.reload()` on both resources every 5s while the page is
open, cleared in `ngOnDestroy`. **No WebSocket** — the transport decision is not yours.

Mark an item whose `slaBreachedAt` is set with `bg-negative-row` and
`border-negative-edge`, matching 1.x's breached row.

### 7.5 · The detail page — `/operations/work-items/:id`

Takes `id` as a signal `input()` (component input binding is on). Shows the item, its
`eventData`, and the audit trail from `getWorkItemAudit(id)` rendered as a `DataGrid`
(action · actor · occurredAt · elapsedSec).

The completion form is a **hand-written textarea** for `correctedEventData` plus
**Complete** and **Park**. ⚠️ **Do not build the data-driven screen renderer.** The
screen layout artifacts do not exist until stream 5; the renderer replaces this form's
body behind the same route later.

---

## 8 · Step 6 — the proof, executed

A page that renders is not a page that works. Do all six, and paste the real output into
your report.

**Keep `ng serve console` running in one process and the browser open**; run these from
the **`orca/` repository root** (you have been working in `frontend/` since gate 6).

1. **Force a work item.** `docs/phase-1-demo.md` §8c, exactly:

   ```bash
   docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -Q "UPDATE runtime.connector_route SET http_status = 418 WHERE connector_name = 'tos'"
   ./deploy/demo/send-plate.py --port 9100 --plate T-WP1-01
   docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -Q "UPDATE runtime.connector_route SET http_status = 200 WHERE connector_name = 'tos'"
   ```

   **Restore the route in the same session, immediately.** Leaving it at 418 sends every
   later truck to a human and silently breaks the next agent's baseline.

2. **The item appears in Queued** in the browser, without a manual refresh (the 5s poll).
3. **Take** moves it to In progress and navigates to the detail page.
4. **Complete** closes it.
5. **Confirm in the database** — the visit reads `MANUAL` and the audit shows `TAKE` then
   `COMPLETE`. Use `phase-1-demo.md` §7's helper:

   ```bash
   q() { docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -h -1 -W -Q "$1"; }
   ```

   (`phase-1-demo.md` §7 writes this helper with `"$MSSQL_SA_PASSWORD"`, which is only set
   if you exported it from `deploy/.env` first. The literal above is the value in that
   file on this machine.)

   ```bash
   q "SELECT TOP 3 external_id, status, plate FROM runtime.execution ORDER BY execution_id DESC"
   q "SELECT TOP 5 w.external_id, w.status, a.action, a.actor, a.occurred_at FROM runtime.work_item_audit a JOIN runtime.work_item w ON w.work_item_id = a.work_item_id ORDER BY a.occurred_at DESC"
   ```

   ⚠️ **There is no `runtime.visit` table — a visit IS `runtime.execution`**, whose
   `status` is one of `ACTIVE`, `COMPLETED`, `MANUAL`. The truck you forced must read
   `MANUAL`, and the audit rows must read `TAKE` then `COMPLETE`.

6. **The browser console is clean.** A page that renders with a console full of errors is
   not done.

Then re-run the §2 gates 6 and 7 and confirm they are still green.

---

## 9 · Commits, if and only if you were told to commit

Do not commit unless the technical lead's launching prompt says so. If it does, these are
the boundaries — one per step, on `feature/console-wp1`, no `Co-Authored-By` or
tool-attribution trailer:

1. `chore(console): remove the theme-check scaffold and wire the workspace` (§3)
2. `feat(console): authenticate against the realm and attach the token to /api only` (§4)
3. `feat(ui-registry): the eight components the queue consumes` (§6)
4. `feat(console): the shell, the routes and the builder placeholder` (§5)
5. `feat(console): the work-item queue and completion form` (§7)

The first commit that lands makes `npm run api:check` real for the first time — it is
inert while `frontend/` is untracked (gate 8).

## 10 · The report you end with

Nine parts, the same shape every work package in this programme uses
(`PARALLEL_STREAM_LAUNCH.md:182-193`):

outcome · commits · changed surface · **evidence (the commands you actually ran, with
their real output)** · what was not built · decisions made within your authority ·
decisions required from the technical lead · **found wrong — reported, not silently
corrected** · next slice.

## 11 · Stop and report — do not improvise past any of these

- An endpoint or field you need is **not in the generated client**. Write down the page,
  the exact shape needed and why. Do not hand-write an HTTP call, do not mock it, do not
  edit a contract.
- A gate in §2 does not produce what it says.
- `git status --porcelain -- projects/api-client/src/lib` is non-empty after
  `npm run api:generate` (the backend moved a contract under you).
- Ports 18081–18083 are already listening when you did not start them.
- You are about to add a dependency that is not `angular-oauth2-oidc`.
- You are about to add a state-management library, a facade layer, a component library,
  or anything inside `console/src/app/builder/`.
- You need a decision about: per-route authorization · localization · export · the
  live-update transport · grid column preferences · the issuer's deployment story ·
  anything security-shaped or commercial. **State the options, give a recommendation,
  then wait.** Filling a gap with something plausible and writing it up as settled design
  is this programme's recurring failure.

## Appendix A · The generated signatures you will use

Read out of `projects/api-client/src/lib/` on 13 Aug 2026. Import only from
`api-client`, never a deep path — both contracts define `ApiResponse`, which is why the
exports are namespaced `runtime.*` and `core.*`.

**`runtime.WorkItemsService`**

```
listWorkItems(status?: 'QUEUED'|'IN_PROGRESS'|'COMPLETED'|'FAILED', laneExternalId?: string,
              assignee?: string, teamExternalId?: string, limit?: number): Observable<WorkItemListEnvelope>
getWorkItem(workItemExternalId: string): Observable<WorkItemEnvelope>
getWorkItemAudit(workItemExternalId: string): Observable<WorkItemAuditEnvelope>
takeWorkItem(workItemExternalId): Observable<WorkItemEnvelope>
takeoverWorkItem(workItemExternalId): Observable<WorkItemEnvelope>
parkWorkItem(workItemExternalId): Observable<WorkItemEnvelope>
assignWorkItem(workItemExternalId, { assigneeExternalId: string }): Observable<WorkItemEnvelope>
completeWorkItem(workItemExternalId, { correctedEventData?: string }): Observable<WorkItemEnvelope>
takeNextOnLane(...)   // note: on WorkItemsService, not LanesService
```

**`runtime.PresenceService`**

```
getMyPresence(): Observable<PresenceEnvelope>
setMyPresence({ status: PresenceStatus }): Observable<PresenceEnvelope>
listAssignableOperators(): Observable<PresenceListEnvelope>
listOperatorActivity(userExternalId: string, limit?: number): Observable<PresenceListEnvelope>
```

**Envelope shape** — every response is
`{ status: 'SUCCESS'|'ERROR', code: string, message?, data?, errors?, page?, requestId? }`.
**`data` is optional on every envelope**, so `?? []` or `?? undefined` at every read
site. Branch on `code`, never on `message`.

**`WorkItem`** — all fields optional: `externalId`, `visitExternalId`, `laneExternalId`,
`processDefinitionKey`, `nodeReference`, `screenExternalId`, `status`
(`QUEUED|IN_PROGRESS|COMPLETED|FAILED`), `assignee`, `queuedAt`, `startedAt`,
`completedAt`, `completionDurationSec`, `slaBreachedAt`, `eventData`,
`correctedEventData`.

**`WorkItemAuditEntry`** — `action` (`TAKE|TAKE_OVER|PARK|ASSIGN|COMPLETE|FAIL|SLA_BREACH`),
`actor`, `previousAssignee`, `occurredAt`, `processingDurationSec`, `elapsedSec`.

**`PresenceStatus`** — `IDLE|WORKING|DND|BREAK|OFFLINE|ACTIVE`.

## Appendix B · Traps that have already cost this programme time

1. **`npx ng build` with no project name fails.** Name each project.
2. **CORS does not exist on any backend service, deliberately.** The dev server proxies
   so the browser sees one origin. **Never add CORS configuration to a backend service** —
   in production the console is same-origin behind the reverse proxy.
3. **The `sub` → platform user link** must be re-applied after every fresh database, and
   verified (gate 5).
4. **The backend authenticates but does not authorize per route**
   (`PlatformSecurityAutoConfiguration` javadoc: "no role mapping, no
   scope-to-entitlement translation, no per-route policy"). Hide actions in the UI where
   sensible, but **do not claim the backend enforces them** and do not invent an
   authorization scheme. Open register item.
5. **A running service poisons the backend integration suite** — they share the `runtime`
   schema. Stop the services before anyone runs `./gradlew integrationTest`.
6. **Tailwind v4 prunes unused theme tokens** — the token block uses `@theme static` for
   exactly that reason. Do not remove `static`.
7. **Both contracts define `ApiResponse`/`ApiError`** — hence the `runtime.*` / `core.*`
   namespacing. Import from `api-client`.
8. **`ng test` uses Vitest here**, not Karma.
9. **Never edit anything under `projects/api-client/src/lib/`** — it is generated.
10. **`sqlcmd` needs `-I`**, or writes to tables with filtered indexes fail with a
    message that names SET options and no table.
11. **The app is zoneless.** Non-signal state does not repaint.
12. **An unwired `BASE_PATH` silently defaults** to the OpenAPI document's
    `http://localhost:808x`, bypassing the proxy. Provide both (§4.6).
13. **There is no `runtime.visit` table.** A visit is `runtime.execution`
    (`external_id`, `status` ∈ `ACTIVE|COMPLETED|MANUAL`, `plate`,
    `process_instance_id`). The API says "visit"; the schema says "execution". Write a
    query against the migrations, never against the noun.
