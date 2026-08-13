# The operator console — build plan

**Status: PROPOSED — technical-lead review before work starts.** Companion to
`frontend-from-1x.md` (the 1.x evidence and design tokens). Angular 22 + Tailwind 4
workspace at `frontend/` is scaffolded and verified.

Scope: the **console** app — what 1.x calls `gate`, minus the two builders (stream 5)
and minus the carrier PWA (portal, cloud scope, deferred). The `kiosk` app is a
separate, later slice: it is a renderer host and the renderer needs stream 5's screen
artifacts.

---

## 1 · The headline: most of this does not need mocking

The premise "build pages with mocks, wire the backend later" is sound, but the
verification changed the picture — **the backend is further ahead than the frontend
planning assumed.** Verified on `develop` @ `f418be3`, 13 Aug 2026:

**52 contracted API paths, all with implemented controllers** (7 in runtime, 17 in
core — every path in both OpenAPI documents maps to one):

| Console need | Endpoints | State |
|---|---|---|
| Work-item queue and lifecycle | `GET /api/v1/work-items`, `/{id}`, `take`, `takeover`, `park`, `assign`, `complete`, `audit` | **Live** — driven by hand through the running stack on 12 Aug (queue → take → complete → audit) |
| Operator presence | `GET/PUT /api/v1/me/presence`, `/operators/idle`, `/operators/activity` | Live |
| Visits | `GET /api/v1/visits`, `/{id}`, `abort`, `/lanes/{id}/visit`, `take-next`, `reset` | Live |
| Users · roles · teams · routing rules | `/api/v1/users`, `/roles`, `/teams`, `/teams/{id}/routing-rules` | Live |
| Devices and the device catalog | `/api/v1/lanes/{id}/devices`, `/devices/{id}`, `io-assignments`, `perspectives`, `/device-types`, `/io-port-names`, `/io-device-kinds` | Live |
| Screens (identity + SLA thresholds) | `/api/v1/screens`, `/{id}` | Live — **identity only, not layout** (§2) |
| Shift and break templates | `/api/v1/shift-templates`, `/break-templates` | Live |
| Settings · site · audit · entitlements | `/api/v1/settings`, `/sites/{id}`, `/audit-events`, `/entitlements` | Live |
| Custom entities | `/api/v1/custom-entities` | Live (stream 3 WP1) |
| **Grid column preferences and saved filters** | `/api/v1/me/workspace/grids`, `/me/workspace/filters` | Live — 1.x's server-driven grid config already ported |

**Consequence for sequencing:** the first several pages are *not* blocked on backend
work at all. They should be built against the real API from day one — mocking what
already exists would invent a second source of truth and hide contract mismatches
until integration day.

## 2 · What is genuinely missing — the only place mocks are needed

| Missing | Owner | Console impact |
|---|---|---|
| Lane monitors, site monitor, grid projections | Stream 2 (branch `feature/orca-stream-2`, unmerged, 23 commits behind `develop`) | The Insights screens. Its branch adds `GridController` + lane-monitor projections — check before duplicating |
| Live updates (WebSocket) | Stream 2 · the cross-instance fan-out decision is **unruled** (`decision-notification-fanout.md`) | Queue auto-refresh. Poll on an interval until it lands; the UI must treat live push as an optimisation, never as correctness (§B4 mechanism 5: "the query is authoritative on reconnect") |
| Screen layout artifacts + `/screens/submit` | Stream 5 (design storage), and the submit path is deliberately unbuilt | **The rendered work-item screen** — the data-driven form an operator fills. Not buildable yet (§3) |
| Async Excel export | Not contracted | 1.x does export-over-WebSocket per grid. Defer; do client-side CSV if a page needs it before then |
| **Per-route authorization** | Open register item — security-shaped, needs its named owner | The chain authenticates but has "no role mapping, no scope-to-entitlement translation, no per-route policy" (`PlatformSecurityAutoConfiguration` javadoc). The console can *hide* what a user may not do; it cannot yet *rely* on the backend refusing it. **Surfaced, not worked around** |

So: **mock exactly four things** (lane monitors, live updates, screen layouts, export),
each behind a service interface, each swapped by deleting the mock — not a mock layer
across the whole app.

## 3 · The one structural warning

1.x's operator flow is **queue → take → a server-defined screen**. That screen is the
data-driven renderer (35 component types), and it needs published screen artifacts that
do not exist until stream 5 ships design storage.

**Do not build the console's work-item screen as the renderer now.** Build it as a
plain, hand-written completion form against `POST /work-items/{id}/complete` (which
takes corrected event data and works today). When stream 5 lands, the renderer replaces
the form's body behind the same route. This keeps the console demoable months earlier
and keeps the renderer where it belongs — one implementation in `ui-registry`, built
once, per §A4/U1.

## 4 · Page inventory and slice order

From the 1.x route map (66 routes), grouped as 2.0 needs them. Order is by *demo value
per unit of work*, not by 1.x's menu.

**Slice 1 — the operator's day (the demo that matters).** Work-item queue (queued +
in-progress), take / takeover / park / assign, the completion form, the audit trail,
presence toggle. All endpoints live. This is the first thing a customer can be shown.

**Slice 2 — visits and lanes.** Visit search grid, visit detail, lane view
("what's on lane X"), lane reset/abort. All live.

**Slice 3 — administration CRUD.** Users, roles, teams + routing rules, devices,
shift/break templates, settings, audit events. All live. Repetitive by design — this is
where the shared grid and form components pay for themselves.

**Slice 4 — insights.** Lane monitors, site monitor, completed work items. Waits on
stream 2 (or mocks, if it is wanted for a demo first).

**Slice 5 — the renderer.** `ui-registry`'s screen renderer + `/screens/submit`, when
stream 5's artifacts exist. Also unlocks the kiosk app.

Deliberately **not** ported: license management (2.0 licensing is a lease-based
instance limit — the 1.x module mostly evaporates), customer management (one
installation = one customer), sync configuration (cloud scope), mobile layout manager
(portal scope), the workflow simulator (stream 5's question).

## 5 · How pages are built — the Angular rules

Verified present in the installed Angular 22: `httpResource`, `resource`,
`linkedSignal`, `toSignal`, and signal forms (`@angular/forms/signals` with
`required`, `email`, `min`/`max`, `pattern`, `validate`, `validateHttp`, `debounce`).

### 5.1 · Signals vs observables — the rule, and why

> **Observables are for streams of events over time. Signals are for state a template
> reads. HTTP is a stream of exactly one thing, so it crosses the border immediately.**

| Need | Use | Why |
|---|---|---|
| Fetch data to display (every grid, every detail page) | **`rxResource()`** | Signal-native: re-fetches when its signal inputs change, and exposes `.value()`, `.isLoading()`, `.error()`, `.reload()`. It removes the loading/error boilerplate that 1.x copy-pastes into 57 files. ⚠️ **Not `httpResource`** — corrected 13 Aug 2026: the generated services return `Observable<T>` and build their own URL from an injected `BASE_PATH`, whereas `httpResource` takes a URL. Using it would mean hand-writing URLs, which `console-implementation-plan.md` §2 forbids. Options are `{ params, stream }`; `value()` throws in the error state, so always pass `defaultValue` |
| A command: take, complete, park, save | **`HttpClient` + `firstValueFrom`** in a service method, then `.reload()` the resource | One-shot action. Nothing to subscribe to over time; `async/await` reads better than a subscribe with an error callback |
| Local UI state: filters, page, selection, dialog open | **`signal()`** | Plain state. Feed them straight into `rxResource`'s `params` so a filter change re-fetches by construction |
| Derived state: counts, enabled/disabled, formatted values | **`computed()`** | Never recompute in the template |
| State that resets when a source changes (selected row when the filter changes) | **`linkedSignal()`** | The one-line answer to a class of stale-state bugs |
| Debounced search input | **RxJS** (`debounceTime`, `distinctUntilChanged`) → **`toSignal()`** | Genuine time-based behaviour — this is what RxJS is for |
| Live updates (WebSocket, later) | **RxJS Observable** → `toSignal()` at the component | A real stream of events. The socket service stays observable-shaped; components see a signal |
| Forms | **Signal forms** (`@angular/forms/signals`) | Stable in 22, signal-native, schema validation. Reactive Forms remain acceptable — pick one per app and do not mix |

**Never:** `subscribe()` in a component (leaks; `rxResource`/`toSignal` handle
teardown) · `async` pipe over a raw HTTP call (re-fires on every check) ·
`mutate()` on a signal (use `set`/`update`) · promises for genuine streams.

### 5.2 · What we deliberately do NOT build

Simplicity is a decision that must be written down, or it erodes:

- **No state-management library** (NgRx/NGXS/Akita). This app's state *is* server state
  plus a handful of UI flags. `rxResource` + signals covers it. Revisit only if two
  distant components must share mutable client state — which has not happened yet.
- **No facade/repository layers.** Component → one typed service → generated client.
  Three layers, not five.
- **No custom RxJS operators**, no shared "base component" class, no dynamic component
  factories outside the renderer.
- **No component library dependency** (Material/PrimeNG). 1.x's look is Bootstrap-plus-
  custom, and matching it through a library's theming is more work than the dozen
  primitives we actually need. Tailwind + `ui-registry` covers it.
- **No server-driven columns on day one.** The contract exists
  (`/me/workspace/grids`), and 1.x drives every column from it. Start with columns
  declared in the component; adopt the preferences contract when the *feature*
  (show/hide, reorder, page size, persisted per user) is actually wanted. That is one
  service and one dialog later, not an architecture change.
- **No i18n plumbing yet** — but keep user-visible strings out of logic, so adding
  Transloco later is mechanical. (1.x loads translations from the server at runtime;
  2.0 has no localization contract yet. Open question, §7.)

### 5.3 · The component set to build (from the 1.x anatomy)

1.x has exactly two real shared components — `LynxisGrid` (1,172 lines) and
`LynxisFilter` (1,867 lines) — used by 27 pages, and **no reusable Button, Modal,
ConfirmDialog, Select, DatePicker, Pagination or Tabs at all**; those are copy-pasted
Bootstrap markup. Its `Toaster` conflates *notification* and *confirm dialog* across
eight status modes, which is why it is imported in 85 files.

Build in `ui-registry`, in this order, and only when a page needs one:

1. `PageShell` — breadcrumb + title + actions slot (kills the per-page copy-paste).
2. `DataGrid` — columns declared with `ng-template` per column key, sort, server
   pagination, empty state, loading state. **The single highest-leverage component.**
3. `FilterBar` — search + cascading selects + date range.
4. Primitives: `Button`, `Select`, `MultiSelect`, `DateRange`, `Modal`, `Toast`
   service, `ConfirmDialog` (**separate from Toast** — do not repeat 1.x's conflation),
   `StatusChip`, `Timer`, `EmptyState`, `Tabs`.

Loading is per-region, not 1.x's full-screen blocking overlay.

## 6 · Work packages

| # | Package | Depends on |
|---|---|---|
| **F0** | `api-client` generated from both OpenAPI documents + CI drift check; delete the `theme-check` scaffold page | — |
| **F1** | App shell: Keycloak auth, route guards, topbar nav (Operations / Insights / Administration), `PageShell`, error/404 routes | F0 |
| **F2** | `DataGrid` + `FilterBar` + primitives, driven by the first real page rather than built speculatively | F1 |
| **F3** | Slice 1 — the operator's day (queue, take/takeover/park/assign, completion form, audit, presence) | F2 |
| **F4** | Slice 2 — visits and lanes | F2 |
| **F5** | Slice 3 — administration CRUD | F2 |
| **F6** | Slice 4 — insights, when stream 2 merges | stream 2 |
| **F7** | Slice 5 — the renderer in `ui-registry` + kiosk host | stream 5 |

Each package ends the way backend packages do: it runs, it is demonstrated against the
live stack, and anything found wrong is reported rather than worked around.

## 7 · Open questions — for the technical lead

1. **Per-route authorization** (register-tracked, security-shaped). The console will
   show admin surfaces the backend cannot yet refuse. Ship read-only admin pages until
   it is settled, or accept UI-only gating in the interim?
2. **Localization.** 1.x loads customer-managed translations from the server. 2.0 has
   no contract for it. Ship English-only now (Transloco later), or design the contract
   with the console?
3. **Live updates before stream 2.** Polling interval for the queue (5s?), or wait?
4. **Excel export.** 1.x's async server export has no 2.0 contract. Client-side CSV in
   the interim, or leave export out of the first slices?
5. **Grid column preferences.** Adopt `/me/workspace/grids` in slice 3, or defer until
   a customer asks for persisted column layouts?
6. **Kiosk timing** — it is a renderer host, so it effectively waits on stream 5.
   Confirm it stays out of scope until then.
