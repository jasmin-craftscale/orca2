# ORCA Phase 0 — Build Report

**Per §9 of the build brief · 7 August 2026 · branch `phase-0-foundations`**

This is the report the brief asks for, written into the repository so the review
does not depend on a conversation. Every claim of the form "verified" below was
produced by running the command, not by writing the code that should satisfy it.

---

## 1 · What was built, package by package

| Package | Commit | What landed |
|---|---|---|
| 1 · Build wiring | `4afde50` | Twelve Gradle modules; root is an aggregator with no code; one version catalog; Java 25 toolchain auto-provisioned (foojay); Spring Boot plugin `apply false` at root, applied per service; generated root application deleted; its Testcontainers pattern preserved as a test-fixtures variant on `platform/outbox`; the OpenAPI generator smoke-tested against Boot 4 before anything depended on it |
| 2 · Local stack | `7926052` (+ parts swept into `219be24`) | SQL Server 2022 + Keycloak 26.7.1 with real health checks; realm imported from a committed file; `.env.example` committed as fixtures |
| 2b · Runtime config | `c4d64fc` | Ports 8081–8086; per-service DB login; Flyway scoped to own schema, `create-schemas` off; actuator health only; issuer at local Keycloak. `deploy/bootstrap/`: 7 schemas, 7 logins, ownership + default schema per login, self-verifying (`V004__verify.sql`) |
| 3 · Migrations | `943dab7` | Per-service `db/migration`; each primitive ships its own DDL, applied into each schema by that service's own Flyway (no shared migrations module); `RequiredViewsGate` fails startup naming every missing published view |
| 4 · Five primitives | `4d2388b` | outbox, lease, scope, idempotency, web — each an interface, one implementation, an auto-configuration, and property tests (details in §3) |
| 4b · Contract-first | `d3362e9`, `5bd278e` | `_shared.yaml` defined and implemented in `platform/web`; six service contracts `$ref` it across the module boundary; `schemaMappings` binds the shared schemas to platform/web's Java types; a second generator pass bundles a self-contained spec into each jar; six hand-written controllers implement six generated interfaces |
| 5 · Build checks | `1991c7a`, `ac0eb0d` | Five ArchUnit rules wired into `./gradlew build`, plus a system-context rule and `ImportedSetGuard` (the check on the checks) |
| 6 · CI | `05a0302` | Three jobs: build+unit+checks (no Docker), integrationTest on real SQL Server, and a local-stack job that bootstraps twice and proves credential isolation on every PR |
| ADR-011 amendment | `c4730b1` | Post-report decision by the product owner: Keycloak authenticates people; services present a per-installation shared credential on `/internal/**`. See §6 |

**Counts:** 12 Gradle modules · 6 bootable applications · 50 unit tests · 31 integration tests · 0 failures · 0 business logic.

---

## 2 · The §7 verification table, with actual results

| # | Item | Result |
|---|---|---|
| 1 | `git clean -xdf && ./gradlew build` | **Pass.** BUILD SUCCESSFUL, 68 tasks, with `--no-build-cache`. Caveat: Gradle's *user-home* cache (dependencies, the provisioned JDK) was warm; a genuinely cold machine downloads them first, which the CI `build` job does on every run |
| 2 | `docker compose up -d` | **Pass.** Both containers reach `healthy` from a fresh volume |
| 3 | Bootstrap against a fresh database | **Pass.** 7 schemas, 7 logins; each schema `AUTHORIZATION`'d to its own user, each user's `DEFAULT_SCHEMA` matching; `V004__verify.sql` asserts all six properties and passed |
| 4 | Start `orca-core` | **Pass.** 3 migrations applied to schema `core`, authenticated as `orca_core` |
| 4b | Restart it | **Pass.** "Schema [core] is up to date. No migration necessary." No error, no duplicate objects |
| 4c | Dependent service before core's views exist | **Pass.** Exit code 1; the log names both missing views and says it is a deployment-ordering problem |
| 5 | `./gradlew test` | **Pass.** 43 unit tests at the time of the run (50 after the ADR-011 work), 0 failures |
| 6 | `./gradlew integrationTest` | **Pass.** 31 tests against real SQL Server via Testcontainers, 0 failures, ~55 s (re-run with `--rerun-tasks` to defeat caching) |
| 7 | `./gradlew check` | **Pass.** All rules green |
| 8 | Deliberately violate each of the five checks | **Pass — after a real fix.** All five now fail the build with a message naming the violation, and the tree reverts to green. **The first run caught check 1 (platform purity) silently NOT firing**: the word matcher required a non-letter after the match, so `VisitResponse` — the most likely violation there is — did not match. Fixed in `ac0eb0d`; the matcher now has its own test with must-fire and must-not-fire cases |
| 9 | Boot all six at once | **Partial.** All six started simultaneously, each answered `/actuator/health` 200, and `sys.dm_exec_sessions` showed six distinct logins. **But on a +10000 port offset**: this machine runs the ORCA 1.x devcontainer stack on exactly 8081–8091, plus tunnels on 1433/8080. Committed configuration keeps the brief's ports. Not proven: that the literal ports bind on a machine where they are free |
| 9b | Cross-schema read with the wrong login | **Pass.** 36 checks: each login writes and reads a probe in its own schema (proving ownership *and* default schema); all 30 cross-schema reads refused with "The SELECT permission was denied" |
| 10 | Token accepted; tampered token rejected | **Pass.** No token → 401 envelope. Valid token → 200. Same token with a modified payload and the original signature → 401. A token signed by a different key → 401. The 401 body never says why |

Item 8's history is worth a sentence: it is the item the brief flags as easiest to
skip, and it did exactly what it exists to do — the check that had never been seen
to fail was in fact not firing.

---

## 3 · The primitives' property tests, by property

All integration tests run against **real SQL Server** (the shared Testcontainers
fixture). None is "the code writes a row".

**P1 · outbox (8):** the database session is killed between the fact write and the
outbox write — neither row survives; a rolled-back transaction leaves neither; a
write outside any transaction is refused, not auto-committed; a drained batch
redelivers nothing; two relay instances over 40 facts on 8 keys deliver everything
exactly once, both doing work; a refused fact blocks its own ordering key and no
other; retention deletes nothing an absent consumer has not acknowledged, however
old; retention also respects the window after full acknowledgement.

**P2 · lease (8 + 5 unit):** sixteen instances race, one wins; a holder whose
lease expired mid-operation has its write **refused and rolled back** (the zombie
case); a stale token cannot renew; expiry is judged by the database's clock; the
fence token strictly increases across six handovers including release-and-retake;
release does not delete the row (so the token sequence cannot restart); the true
holder is not blocked by its own guard under concurrency. The unit tests are
`LeaseConfigurationValidator` — §C2's "refuses to start" rule, which *is* the
specification since the architecture states no numbers.

**P3 · scope (8):** with no scope set, the seam returns **zero rows while four sit
in the table**; explicit `DENY` likewise; a scope that says nothing about *this*
table's dimension is deny, not permission; a permitted scope returns exactly its
rows; a caller filter of `status = ? OR 1 = 1` still cannot escape; the scope
restores after the work (pooled threads); an unscoped select is not expressible;
identifiers are allow-listed by shape. Property 1 — "a query built outside the
seam fails the build" — cannot be a test and is the `ScopeSeamRule` build check,
proven under item 8.

**P4 · idempotency (7):** a replay returns the recorded outcome, not "duplicate";
a recorded *failure* is also a recorded outcome; keys are scoped per operation; an
in-flight replay is `InProgress` and the sealed type makes it impossible to read
an outcome that does not exist; a terminal record without an outcome cannot be
written (application check *and* database CHECK); 32 concurrent first attempts —
exactly one executes; release is holder-scoped.

**P5 · web (27 unit):** eight kinds of failure, each carrying a poisoned message
(schema-qualified table, SQL fragment, stack frame, config path, internal host,
password), serialised and asserted clean — including the exception's own class
name; a deliberate `ApiException` message *does* survive, so the handler is not
blanking everything; every failure pair gets distinct codes; `SystemContext`
refuses nesting, clears on every path including checked exceptions, and is
thread-confined; the inter-service filter and credential validator (§6).

---

## 4 · What was not built, and why

- **The closed retention-class list.** §B10/§C2 declare it closed (18 values, a
  database `CHECK`); the list lives in the Data Dictionary, which is not in this
  repository, and the register records its two published copies disagree. The
  build check enforces that a class is *named* — exactly what §B10 specifies — and
  the three names used are marked PROVISIONAL in the source.
- **Any authorization rule, and any tenancy mechanism.** `Scope` is deliberately
  opaque about what a dimension is; a tenant column baked in would decide NEW-1a
  by accident in the direction that cannot express the portal's per-principal
  rule. Row-level security is unbuilt per the brief; the connection-pool trap is
  documented in `platform/scope/README.md` for whoever builds it.
- **Lease durations.** The architecture states none; the startup validator is the
  stated specification. Local values are marked local.
- **A seventh OpenAPI contract for `orca-media`.** Contradiction in the brief —
  see §7.
- **Consumer registrations, published views, scheduled relays.** Nothing consumes
  another service's facts in Phase 0, core publishes no views yet, and no relay is
  scheduled. The mechanisms exist and are tested; the wiring is Phase 1's.

---

## 5 · Decisions the architecture did not dictate

These are what the team should review, in rough order of consequence.

1. **`@PersistentTable` / `@RetentionClass` live in `platform/scope`.** The
   retention build check needs a declaration visible to every service; the brief
   fixes the module count at twelve. Scope is the data-access primitive, so the
   table declarations sit there — flagged in its README as the first thing to move
   if a sixth primitive is ever wanted.
2. **A shared default `SecurityFilterChain` in `platform/web`.** §B6 states one
   platform-wide validation property; six hand-rolled chains is six chances for
   one to differ. It decides no authorization rule, and a service defining its own
   chain displaces it.
3. **`orca.web.public-docs=false` by default.** Whether an installation publishes
   its API surface is a security decision with a named owner; only the `local`
   profile turns it on.
4. **The Testcontainers fixture is a test-fixtures variant on `platform/outbox`**,
   not a thirteenth module.
5. **Primitives ship their own Flyway locations**, applied into each schema by the
   owning service's Flyway. One definition, six schemas, no shared runner, and the
   `service_lease` duplication is ADR-004's stated cost (register item S2), not
   resolved here.
6. **No blanket `DENY` in the bootstrap.** Confinement is already total (no grant
   → no access), and `DENY` overrides `GRANT` — it would silently defeat ADR-009's
   future view grants and the failure would look like a bug in the view.
7. **`platform/` is exempt from the scope-seam rule**; its state has no tenant
   dimension (§C2 says so for the lease), and platform purity keeps the exemption
   honest.
8. **Flowable's `database-schema-update: true` written out explicitly** rather
   than left invisible — see §7.
9. **`/api/v1/health` as the one contracted route per service**, distinct from
   actuator's probe, so the contract → generated interface → hand-written
   controller → shared envelope loop is exercised end to end from day one.
10. **Sixth build-check rule (system context)** beyond the brief's five: §B10's
    "assert every scheduled job, relay and reconciler enters it" can only be a
    rule over every class, not a unit test.

---

## 6 · Post-report ruling: ADR-011 narrowed (7 Aug 2026)

The product owner ruled that **Keycloak authenticates people, not services.**
Service-to-service calls present a per-installation shared credential on
`/internal/**`, validated locally; nothing on the gate path mints a token. The
reason is availability: token *issuing* — unlike validation — always requires the
IdP to be reachable, which register item #7 showed no caching fixes.

Built and verified the same day (`c4730b1`): the filter (constant-time
comparison, `ROLE_ORCA_SERVICE` and nothing more — the claimed service name is
attribution, never authority, since a shared credential cannot prove which peer
is calling), the authorization split (a user token cannot reach `/internal/**`;
the credential means nothing elsewhere), and a startup validator that **refuses
the committed `.env.example` fixture outside the `local` profile by name**, so
the public value cannot reach a site by inertia.

Register items #7 and U3 are **narrowed, not closed**: what remains of #7 is
purely whether offline *operator* login is promised (a Product question), and
U3's remaining surface is the user-facing realm structure, still blocked on
NEW-1a for the portal.

---

## 7 · Things in the architecture that look wrong or self-contradictory

Reported, not corrected — except the first, which the product owner has since
ruled on.

1. ~~**ADR-001/§A6 say Java 21 / Boot 3** while everything else says 25/4.~~
   **Resolved 7 Aug:** confirmed as deliberate; ADR-001 re-signed in this
   repository's copy. ⚠️ The Lynxis-Gate copy of the corpus still needs the same
   edit; this session cannot reach it.
2. **Seven contracts versus a README.** §4b: "all seven contracts share the
   envelope"; §3/§6: `orca-media` is a README, not a module, not built here. Both
   cannot hold. Six were authored; `services/orca-media/README.md` records why.
3. **`topology.*` versus "seven schemas".** §C1 names published views as
   `topology.customer` etc. — which reads as a schema — but §B5's schema list has
   no `topology`. Under ADR-004 the difference decides bootstrap grants and the
   readiness-check names. Phase 0 publishes no views, so nothing was decided; the
   gate takes fully qualified names either way.
4. **Nobody migrates Flowable's ~46 tables.** §C2 puts them in the `runtime`
   schema; nothing says who creates them or how that interacts with §B7's
   rolling-upgrade / expand-only discipline. The engine's self-migration default
   is written out visibly in `orca-runtime`'s configuration and left as found.
5. **`REPOSITORY_GUIDE.md` was stale at kickoff** (13 modules, a root
   `contracts/`, a shared `migrations/` module the brief forbids). Updated
   mid-session by the orchestrator; noted for completeness.

## 8 · Known environmental caveats

- §7 item 9 ran on a +10000 port offset (this machine runs ORCA 1.x on the
  assigned ports). Committed configuration uses the brief's ports.
- The SQL Server image is amd64-only; on Apple silicon it runs emulated. Slow,
  correct, and deliberately not swapped for `azure-sql-edge`.
- CI exists but its checks are not yet marked required — that is a
  branch-protection setting on the repository, noted in the workflow file.
