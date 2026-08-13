# Frontend — DERIVED-FROM-1X reference (visual language + port feasibility)

**Sources:** `Lynxis-Gate/frontend/{gate,pwa-web-console,kiosk-console,orca-installer}`,
read-only, extracted 13 Aug 2026. Consumed by `frontend/projects/ui-registry/styles/orca-theme.css`
(the token file) — a value changes there only when this sheet changes.

## 1 · The 1.x estate, and what 2.0 owes now

| App | Scale | Is | 2.0 disposition |
|---|---|---|---|
| `gate` | 255k lines, ~66 routes (`App.tsx:302-1290`) | Admin config + operator work + monitoring, one RBAC-gated SPA | Splits into **console** app + **builder** feature folder |
| `kiosk-console` | 42k lines — 91% renderer | Driver touchscreen at the lane; device-authenticated by URL codes (`App.tsx:73-82`); iframed into gate for operator preview | **kiosk** app (renderer shell) |
| `pwa-web-console` | 69k lines | Carrier/driver PWA (offline tickets via dexie, lane-pairing QR scan via zxing, raw Web Push in a hand-written SW) | Portal scope — cloud tier, deferred |
| `orca-installer` | 54 files, Electron | Customer environment setup | Deployment phase, later |

**Feasibility verdict (checked dependency-by-dependency): everything is buildable in
Angular; nothing is React-locked.** The only React-specific libraries are `reactflow`
(workflow canvas → Foblex Flow, ruled, register 15) and thin bindings (`react-konva`,
`react-dnd`) over framework-agnostic cores. Load-bearing libs that port unchanged:
`jssip` (SIP intercom, `AudioPanel.tsx`), `konva` (PTZ joystick overlay,
`LyStreamVideoPlayer.tsx`), Monaco (JSON editing ×3 sites), native WebSocket
(37 call sites), WebRTC/`RTCPeerConnection` + Axis MJPEG web component (camera video,
four transports), exceljs/zxing/dexie/simple-keyboard/keycloak-js.

**The renderer is the port's centre of gravity:** 35 component types
(`responseBuilderComponents/contants/RenderChild.tsx:48-123`), forked into **three
copy-pasted trees** (gate 57 / pwa 98 / kiosk 92 files, ~91k lines; provably
copy-paste — `RenderSIngleElements.tsx`, typo included, byte-identical in two apps;
36 files silently diverged). 2.0 builds it **once**, in `ui-registry` (§A4/U1).

## 2 · The visual language (token evidence)

The real design system is **Bootstrap 5 + `public/scss/variables.scss` + a runtime
CSS-variable layer** — there is no MUI theme (`createTheme` grep: zero hits; MUI used
in 3 files, styled by CSS override). Six brand colors are site-overridable at runtime
(`App.tsx:174-290` maps an API array positionally onto CSS vars) — 2.0 keeps the
override-by-CSS-variable mechanism (core owns `site_color`), not the defects (below).

Key values (full set in `orca-theme.css`; evidence `variables.scss:104-190` unless noted):

- **Brand:** primary `#296FF0` (light `#ECF2FE`, dark `#0F55D7`), secondary `#EDEDED`,
  logo navy `#2B388F` (tints every shadow: `common.scss:1963`), cyan `#00ADEB`.
- **Surfaces:** body `#F9FAFB`, cards white, table header `#ECF2FE`, row divider
  `#ECF3FF`, border `#DEE0E9`. Shadows are flat and navy-tinted (`0 0 2px #D4DCFBF2`).
- **Text:** body `#151618`, table header `#344155`, muted `#777777`, label `#5D6479`.
- **Status:** positive `#33C475`/bg `#E7FFF2`; negative `#F06868`/bg `#FFEAEA`
  (breached row `#FDEAEA` + border `#F7AEAE`, `table.scss:56-70`); neutral `#FDA700`;
  info = primary; **work-item queued `#FE9900`/`#F5EFF4` and in-progress
  `#576CF3`/`#E5FAFA`** — off-palette but what operators know; hardcoded twice in 1.x
  (`LaneAlertsGrid.tsx:816-845`), tokenized once in 2.0. Lane state colors are
  **data-driven** (API `color_code` per row) — not tokens, port as data.
- **Type:** self-hosted Open Sans (four weight-families → one family, weights
  400/500/600/700 via `@fontsource`); grids 13px, buttons 12px, nav 14px; +2px ≥1700px,
  +4px ≥2000px (`variables.scss:5-15` — a idiom to reproduce deliberately or drop, §4).
- **Shape/density:** radius 6px dominant (buttons/inputs), 8px cards, 10px modals;
  compact tables (`8px 16px` cells, `table.scss`); buttons `10px 16px`, 40px inputs.
- **Shell:** white sticky topbar (~56px, 40px logo), black links → primary on
  active/hover, no persistent sidebar (per-page left accordion where needed:
  active bg `#ECF2FE` + 2×18px primary rail, `common.scss:1877-1893`).
- **No dark mode** in 1.x (grep: zero hits).

## 3 · 1.x frontend defects deliberately NOT carried forward

1. Renderer forked ×3 with silent drift (a crash guard fixed in one fork only:
   pwa `LyVirtualKeyBoard.tsx:36-39` guards `JSON.parse`, kiosk copy does not).
2. Two divergent lighten/darken algorithms (runtime RGB-mix `App.tsx:119-144` vs
   compiled Sass HSL) — same variable, different colors depending on API availability.
   2.0: shades are fixed tokens; a branding feature derives them with one algorithm.
3. Positional API→color mapping (`App.tsx:256-262`) — reordering the array silently
   swaps primary and negative.
4. Hardcoded off-palette status hexes duplicated across grids (now tokens).
5. `.progress-timer` variants with no CSS fallback (invalid pre-login);
   `.gradient-tag.neutral` referenced but never defined.
6. 15 files importing `isString`/`newUUID` from `jssip/lib/Utils` (accidental).
7. `firebase` as a type-only dependency (~10 MB for one interface); push is actually
   raw Web Push in the hand-written service worker.
8. 58 of 66 routes eagerly imported (one 1,300-line `App.tsx`); per-component
   hand-rolled WS reconnect loops ×37.

## 4 · Open items for the tech lead

- Reproduce the ≥1700px/≥2000px responsive font step-up, or standardize on one size?
  (Operator walls at terminals are large screens — the idiom may be intentional.)
- Dark mode: 1.x has none; decide whether 2.0 introduces it (tokens make it cheap
  later; not doing it now).
- Work-item status colors: keep 1.x literals (current choice, familiarity) or fold
  into the semantic palette at first console review with the PO.

## 5 · 2.0 wiring (state as of 13 Aug 2026)

⚠️ **Foblex Flow (stream 5's canvas) is at 19.1.6 and declares `@angular/core >=17.3.0`
— an open upper bound, so npm will install it against Angular 22 without complaint but
the library has not been released against it.** Verify on a branch before the builder
work starts, and pin the exact version once verified (register item 15's hedge — the
canvas stays wrapped behind the builder's own component layer either way).

Angular 22 workspace at `frontend/` (`projects/{console,kiosk,ui-registry,api-client}`),
Tailwind v4 via `.postcssrc.json`, tokens in
`projects/ui-registry/styles/orca-theme.css` (`@theme static` — static is load-bearing,
v4 prunes unreferenced tokens otherwise), Open Sans via `@fontsource` (self-hosted;
the appliance has no internet). Both apps import `tailwindcss` + fonts + theme in
`src/styles.css`. Verified: all four projects build; tokens and 40 `@font-face` rules
present in emitted CSS.
