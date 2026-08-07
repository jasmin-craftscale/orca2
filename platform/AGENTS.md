# `platform/` — the primitives

Five primitives, and **never domain**. A class here that names a visit, a lane, a
ticket, a driver or a truck fails `PlatformPurityRule` — in its class name, a
field name or a method name, camelCase-aware and plural-aware. So does any
dependency from `platform/` on a service package.

What each one prevents, and the named pattern behind it, is
`docs/PLATFORM_PRIMITIVES.md`. Read it before changing one — **each primitive
exists to make a specific defect impossible**, and the defect is never the one the
code appears to be about:

| Module | The defect it makes impossible |
|---|---|
| `outbox` | The fact and the notification disagreeing, with nothing to detect it |
| `lease` | A stalled instance waking up and completing a write it no longer owns |
| `scope` | A `WHERE site_id = ?` written 300 times and omitted once |
| `idempotency` | A retry receiving *"duplicate"* — the one answer it cannot use |
| `web` | Callers parsing message strings, and work running with no identity |

## Changing a primitive

The property tests are the specification. Changing an implementation means its
tests **still prove the property** — not that they still pass. A test rewritten
until it goes green proves the code does what the code does.

Four integration suites carry those properties, and **`./gradlew test` does not
run them**:

- `platform/outbox` → `OutboxPropertiesIT`
- `platform/lease` → `LeasePropertiesIT`
- `platform/scope` → `ScopeSeamPropertiesIT`
- `platform/idempotency` → `IdempotencyPropertiesIT`

They need `./gradlew integrationTest` and a Docker daemon — real SQL Server via
Testcontainers, never an in-memory substitute. `platform/web`'s properties are
unit tests and do run under `test`.

## Where two things live that are not where you would look

- **`@PersistentTable`, `@RetentionClass` and `Growth` are in
  `platform/scope/.../scope/table/`**, not in a module of their own. The retention
  check needs a declaration visible to every service and the module count is fixed
  at twelve. Recorded in `phase-0-report.md` §5.1 as the first thing to move if a
  sixth primitive is ever wanted; `RetentionClassRule` imports them from there.
- **`platform/web` carries three things**: the response envelope (`ApiResponse`,
  `ApiError`, `ErrorCode`, and the handler that never serialises an exception or a
  schema name), `SystemContext` under `web/system/`, and the ADR-011 internal-call
  filter under `web/internal/` — which authenticates `/internal/**` against the
  per-installation shared credential and refuses to start on the committed
  fixture outside the `local` profile.

`platform/` is exempt from `ScopeSeamRule`, deliberately: its state has no tenant
dimension. Platform purity is what keeps that exemption honest — a primitive that
cannot hold tenant data cannot leak it. Do not add domain state here and then
reason about the exemption.
