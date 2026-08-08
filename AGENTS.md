# ORCA — repository instructions

## What this repository is

ORCA 2.0 is gate automation for logistics facilities — container terminals and
distribution centres, where a truck arrives at a lane, a camera reads its plate,
and the process the site's own administrators designed runs against the customer's
systems until the barrier lifts. It is a ground-up rewrite of a system in
production today (25 Go microservices, 4 React applications, SQL Server) that
**this repository does not contain and does not migrate from** — see
`docs/ORCA_ORCHESTRATOR_HANDOVER.md` §3. Phase 0 built the foundations — twelve
Gradle modules, six bootable services, five platform primitives, seven build
checks. **Phase 1 built the first vertical slice on top of them and it runs end to
end**: a plate read in over the camera's wire format, one visit, a connector call,
a barrier commanded and confirmed, and the visit's fact recorded in one
transaction. **Phase 2 made orca-core's configuration world real** — identity and
the entitlement catalog, teams and templates, the completed device registry,
settings/workspace/audit — translated from 1.x, never copied. Everything else is
still deliberately absent.

Two laws shape everything else:

> **The gate must keep working when other things do not.**
>
> **The site's processes belong to the site.**

## Where the truth lives

This file is a map. It does not restate the architecture — follow the link.

| Question | Document |
|---|---|
| What is the target design, and what does it guarantee? | `docs/ORCA_ARCHITECTURE.md` — **read §B10 first; it is the acceptance criteria** |
| What is deliberately unsettled? | `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` |
| What are the five primitives, and what pattern is each? | `docs/PLATFORM_PRIMITIVES.md` |
| Where does anything live in this repository? | `docs/REPOSITORY_GUIDE.md` |
| What did Phase 0 build, decide, and fail to settle? | `docs/phase-0-report.md` |
| What did Phase 1 build, and what is still guessed? | `docs/phase-1-report.md` — **§3, §5 and §7** |
| What did the hardening after it change? | `docs/phase-1-hardening-report.md` — **§5 is what it found and did not fix** |
| What did Phase 2 build, decide, and leave as named gaps? | `docs/phase-2-report.md` — **§5.1 (the realm dimension), §6 (three PROPOSED security designs), §7** |
| How do I run the slice end to end? | `docs/phase-1-demo.md` |
| What does the camera actually put on the wire? | `docs/lpr-wire-format-from-1x.md` — DERIVED-FROM-1X, not a vendor spec |
| What does ORCA send a device host to move a barrier? | `docs/device-host-outbound-from-1x.md` — DERIVED-FROM-1X; **§3 is an open question, not a design** |
| The engine created its own tables on this database. Now what? | `docs/flowable-adoption.md` — the procedure, and §4 says what it deliberately will not do |

**If something is unspecified, check the register before concluding it was
forgotten. Never invent a resolution — a gap reported is worth more than a gap
filled.**

## Hard rules, and the check that fails when you break one

Every rule below is executed, not reviewed. The classes live in
`build-checks/src/test/java/com/lynxis/orca/checks/` and run under
`./gradlew check`.

| Rule | Enforced by |
|---|---|
| No class in `platform/` names a visit, lane, ticket, driver or truck — in its class name, a field or a method — and no class in `platform/` depends on any service package | `PlatformPurityRule` |
| No `orca-runtime` module depends on another module's `persistence` **or** `domain` package | `ModuleWallRule` |
| No service depends on another service's packages — a Java import is not one of the five permitted mechanisms (§B4) | `ModuleWallRule` (it carries three tests, not two) |
| No service class touches `EntityManager`, `CriteriaBuilder`, `JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcClient`, `DataSource` or raw JDBC, and no service class extends a Spring Data repository. Every read goes through `platform/scope`'s seam. `platform/` is exempt: its state has no tenant dimension | `ScopeSeamRule` |
| Every public instance method of a class annotated `@RestController` returns the shared envelope — `ApiResponse`, or a generated model carrying an `ApiStatus` field, or `void`. `ResponseEntity<T>` is unwrapped to `T` from the generic signature | `ErrorEnvelopeRule` |
| Every class annotated `@Entity` declares `@PersistentTable`, whose `growth()` has no default; every table declared `TRAFFIC_GROWING` names a non-blank `@RetentionClass` | `RetentionClassRule` |
| Every method annotated `@Scheduled` calls `SystemContext.runAs` or `SystemContext.callAs` — no path runs with no identity | `SystemContextRule` |
| No class outside `services/orca-runtime`'s `execution` package depends on `org.flowable` — the engine is reached behind `ProcessEngineGateway` (§C2, ADR-006) | `EngineConfinementRule` |
| Every table declaring the scope column `site_external_id` has **some index leading with it** — the key, a unique index or an ordinary one. Every seam read leads with the scope predicate, so a table without one can only be scanned, and a scan under `UPDLOCK` locks every row at the site | `ScopeIndexRule` (reads the migrations, not bytecode) |
| Every class annotated `@RestController` implements an interface from a generated `*.api.generated` package — the only thing that makes a contract change break the build | `ContractInterfaceRule` |
| Every operation an OpenAPI document tags `internal*` maps under the ADR-011 filter's own path pattern, and every path under that pattern is tagged internal. No third surface | `InternalSurfaceRule` (reads the contracts; takes the pattern from `InternalCallProperties`) |

Two more rules are real but enforced **outside** ArchUnit, so no build check will
tell you:

- **A service reaches only its own schema.** Seven schemas, seven logins, granted
  by `deploy/bootstrap/`; `V004__verify.sql` asserts the confinement. The database
  refuses a cross-schema read — nothing in Java does.
- **Internal endpoints live under `/internal/**`,** behind the per-installation
  shared credential (ADR-011), validated locally. The identity provider
  authenticates **people only**; no service mints a token to call another, because
  token *issuing* would put the identity provider on the gate path.

## How to work

**Contract-first, never controller-first.** Edit the service's OpenAPI document at
`services/<name>/src/main/resources/openapi/orca-<name>.yaml`, regenerate, then
implement the generated interface. The generator emits interfaces only; the
controller is hand-written. Change the contract and the build breaks until the
controller matches — that is the point.

**Tests prove properties, not paths.** The canonical example:

> *"The outbox writes a row" is not a test — "killing the process between the two
> writes leaves neither" is.*

**Build checks land with the code they govern, never after.** A check added once
the code exists certifies whatever was written. A new convention arrives with a
new check, or it is labelled *convention* so nobody mistakes prose for
enforcement.

## Commands

Every command below was executed against this repository while this file was
written.

```bash
./gradlew build              # compile, unit tests, build checks — the whole tree
./gradlew test               # unit tests only
./gradlew check              # unit tests + the ten build checks
./gradlew integrationTest    # 138 property tests, real SQL Server, real Flowable
```

⚠️ **`test` runs almost none of what proves this repository.** `integrationTest`
is a separate source set and a separate task, deliberately **not** wired into
`check`, so that `build` succeeds on a machine with no Docker daemon. What it
skips is eighteen suites and every property that matters — the admission race, the
severed link, the lease handover, the expired command, the outbox's atomicity.
**Full verification is `./gradlew check integrationTest`.**

⚠️ **A green `check` does not mean a service starts.** Every suite constructs its
beans directly rather than refreshing a context, so a broken bean definition
passes all of them — this is how Phase 1 shipped an `orca-edge` that could not
boot. See `phase-1-report.md` §7.10. Run the demo.

The local stack — SQL Server, Keycloak and WP7's two stubs. There is no broker. The
one-time setup, including the `.env` you must create first, is `deploy/README.md`;
follow it rather than a copy of it. **Do not blind-copy `.env.example` over an
existing `.env`** — it is gitignored precisely because it holds machine-local
values. Once it exists:

```bash
cd deploy && docker compose up -d && docker compose run --rm bootstrap
```

The bootstrap is the privileged half — schemas, logins, grants. It runs once
against a fresh database and is a no-op afterwards. Run one service:

```bash
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local'
```

Add `--server.port=<free port>` when 8081–8086 are already taken. The committed
ports are the architecture's and are not the thing to change.

⚠️ **The `local` profile is not optional on a laptop.** The committed
inter-service credential in `.env.example` is recognised by name, and
`InternalCredentialValidator` **refuses to start** the service with it unless
`local` is active — so the public fixture cannot reach a customer site by inertia
(ADR-011). Set the profile; do not weaken the check.

The Java 25 toolchain is auto-provisioned by the foojay resolver, so a clean clone
builds with nothing installed but a JDK. Dependency coordinates come from
`gradle/libs.versions.toml` and nowhere else — **Spring Boot 4 renamed the
starters** (`spring-boot-starter-webmvc`, not `-web`; each starter has its own
paired `-test` artifact).

## Decision discipline

Settled decisions are the seventeen ADRs in `ORCA_ARCHITECTURE.md` §D1. They are
not reopened in code — a decision quietly reinterpreted in an implementation is
how the last corpus rotted. Five have already changed, and each was **re-signed**
rather than reinterpreted.

**Current programme scope: the on-site system first.** `orca-portal`, `orca-sync`
and `orca-fleet` stay Phase 0 skeletons until cloud scope opens; nothing new is
built on them yet (register NEW-1b).

Anything security-shaped, commercial, or scope-changing is the product owner's
call. **Surface it — do not settle it.**

## Definition of done for substantive work

1. The build is green, **including** `check` and `integrationTest`.
2. Every guarantee touched has a property test that would fail if the guarantee
   broke.
3. A short written report: what was built · what could not be · every decision the
   specification did not dictate, with the reasoning · anything that looked wrong
   — **reported, not silently corrected.**

## Maintaining this file

This file is the single source of truth for instructions, and the adapter files
beside it import it rather than copying it — two divergent copies of one rule is
how the last corpus rotted.

The 200-line budget is deliberate, not cosmetic: adherence measurably drops as
instruction files grow, and this file loads into context in full at every launch.

Every `@`-prefixed token here stays inside backticks, because some agents parse a
bare `@path` as a file import.
