# Stream 1 implementation report

**Status:** A1 implemented and locally verified; ready for independent A1 review.

**Branch:** `feature/stream-1-a1`

**Base:** `origin/develop` at `62b5b3f`

**A1 commit:** `2f40cee`

**Recorded:** 11 August 2026

This report currently covers **A1 only**. It does not claim any later Track A or
Track B work package.

## A1 · Admission seam

### Built

- `execution.api.PartnerEventAdmissionPort` is the legal cross-module contract.
  Its API-owned event, outcome, status and lane exception expose no
  `execution.domain`, `execution.persistence` or Flowable type.
- `PartnerEventAdmissionAdapter` implements the port over the existing
  `AdmissionService`. The adapter does not reproduce admission: it retains the
  lane lock, filtered-index backstop, one transaction and correlate-or-start.
- Partner event keys use the persisted idempotency operation `partner-event`,
  distinct from the existing `device-event` operation. The operation is fixed by
  the execution-side adapter rather than chosen by a caller.
- `AdmissionService` now accepts an execution-internal operation namespace while
  its existing device entry point continues to use `device-event` unchanged.
- `integration.domain.PartnerEventAdmission` consumes only the new
  `execution.api` port. It establishes the installation site scope from
  configuration; no site identifier is accepted from a partner event.
- Admission preserves all four answers across the module wall:
  `STARTED`, `CORRELATED`, `DUPLICATE`, and `IN_PROGRESS`.
- No contract, migration, dependency or framework was added.

### Property proof

`PartnerEventAdmissionPropertiesIT` runs against real SQL Server and Flowable.
Its load-bearing case performs **200 iterations** on one lane. Each iteration:

1. releases two distinct partner events for the same truck simultaneously;
2. requires exactly `STARTED + CORRELATED`;
3. requires both outcomes to carry the same visit id; and
4. completes the active visit before the next truck.

The final assertions require exactly **200 `execution` rows**, **400
`execution_event` rows**, and **400 `partner-event` idempotency rows**. Three
additional cases prove replay returns `DUPLICATE` with the original visit, a
completed `device-event` record cannot answer for the same partner key, and an
owned claim remains `IN_PROGRESS` with no visit or event effect.

Focused result:

```text
PartnerEventAdmissionPropertiesIT
tests=4 failures=0 errors=0
200 partner trucks, two concurrent submits each: exactly 200 visits
case time=15.577s
BUILD SUCCESSFUL in 22m 46s
```

The uncached full-suite rerun repeated the same property case in `4.478s`, also
green.

## Verification evidence

The Windows host could compile with Gradle, but host Testcontainers could not
communicate correctly with Rancher Desktop's Docker named pipe, and OneDrive made
Gradle output cleanup unreliable. The documented commands were therefore run in
the existing `gradle:9.5.1-jdk25` image with the repository, Gradle cache and
Docker socket mounted. `TESTCONTAINERS_CHECKS_DISABLE=true` avoids only the
startup probe; the suites still created and exercised their real SQL Server
containers. All commands used `--offline`; no dependency changed.

### Pre-change baseline

- `a0bc010` was proven an ancestor of `origin/develop`.
- Branch ownership audit found no commits or A1 changes. Only the three permitted
  local line-ending files were dirty.
- `./gradlew check integrationTest --rerun-tasks` equivalent: Docker runner exit
  `0`; **33 integration suites / 266 tests / 0 failures / 0 errors**.
- Pre-change live canary: event
  `evt-a0dc1d62-4767-40a7-a725-5e485876d94c`, plate `T-A1-BASE-01`:
  buffer `ACKED`, visit `vis-74a657d1-9737-4e9c-9d0a-d2c5413b07fd`
  `COMPLETED`, barrier command `EXECUTED`, `visit.completed` at outbox sequence 2.
- Services were stopped before implementation and testing.

### Post-change gates

| Gate | Result |
|---|---|
| Focused A1 suite | `:services:orca-runtime:integrationTest --tests "*PartnerEventAdmissionPropertiesIT" --rerun-tasks`: 4 tests, 0 failures/errors |
| Full build | `./gradlew build --rerun-tasks`: `BUILD SUCCESSFUL` in 7m 15s; 73 tasks executed |
| Uncached full suite | `./gradlew check integrationTest --rerun-tasks`: `BUILD SUCCESSFUL` in 20m 7s; 73 tasks executed |
| XML count | Unit/build checks: **19 suites / 80 tests / 0 failures / 0 errors / 0 skipped**. Integration: **34 suites / 270 tests / 0 failures / 0 errors / 0 skipped** |
| Module wall | Full build checks green; production `integration` contains no import of `execution.domain` or `execution.persistence` |
| Isolation | `docker compose run --rm verify-isolation`: **PASS — 36 checks** |
| Six-service boot | Core first, then runtime, edge, portal, sync and fleet: health `200` on ports `18081`–`18086` |
| Phase 1 live regression | Event `evt-5548c194-e152-4489-99f7-fd9ac2325caa`, plate `T-A1-POST-01`: buffer `ACKED`, visit `vis-914a34c1-c5a5-46c1-a18d-ac5f1c6c47b7` `COMPLETED`, barrier `EXECUTED`, `visit.completed` at outbox sequence 3 |
| Final process state | `NO_JAVA_OR_GRADLE_PROCESSES`; no listeners on `18081`–`18086` or `9100` |

The stream plan's live `POST /submit` proof was **not run**, because that route is
an A3 deliverable and A3 was deliberately not started. Claiming it for A1 would
either require starting A3 or inventing a route outside this branch. A1's partner
path is proven through the integration-owned consumer and its real database/
Flowable property test; the existing shipping HTTP gate path is proven by the live
Phase 1 canary above.

## The seven 1.x inversions

| Inversion | A1 position |
|---|---|
| 1 · read-then-insert dedup | A1 reuses the atomic idempotency primitive and the single admission implementation. The 200-iteration concurrent property proves one visit per truck. The A2 queue row does not exist yet. |
| 2 · two status vocabularies | Not built; A2 owns the queue state machine. A1 only preserves admission's existing four outcomes without flattening. |
| 3 · partner must close platform rows | Not built; A4 owns self-closing dispatch rows. |
| 4 · no tenant/scope column | No A1 table exists. The integration consumer applies configured site scope. A2 owns the scoped queue table and leading index. |
| 5 · unbounded payload | Not built; A2 owns the payload bound and retention declaration. |
| 6 · unbounded/unreconciled claim | Not built; A2 owns bounded claiming and recovery. |
| 7 · unresolved route strands work | Not built; A2/A3 own resolution and the public error taxonomy. |

## Decisions made within A1 authority

1. **Port owns partner semantics.** A partner-specific port was chosen instead of
   exposing an arbitrary operation argument across the module wall. Cost: another
   inbound producer would publish another deliberate adapter. Benefit: a caller
   cannot select `device-event` and create a cross-producer collision.
2. **Persisted namespace is `partner-event`.** It follows the existing
   `device-event` vocabulary and is treated as a stored identifier, not display
   text. Cost: renaming it later would require compatibility with existing
   idempotency rows. The namespace-separation property proves the distinction.
3. **Scope is applied by the integration consumer.** This makes the A1 consumer
   safe before A3 adds a controller and preserves the rule that a request never
   selects a site.
4. **No device identity is invented for a partner.** The execution event's nullable
   `device_external_id` remains null. A1 has no authority or evidence for a
   synthetic device id.

## Decisions and questions left open

No product, security or scope question was resolved in A1.

- **Q1 `/callback` correlation:** still open; requires the product owner and
  builder developer. No route, process or engine correlation method was added.
- **Q2 partner credential/client structure:** still open under register U3. No API
  key or Keycloak client structure was invented.
- **Q4 retention class:** deferred to A2 and Stream 4; A1 added no table.
- **Q5 priority ordering:** deferred to A2; no queue ordering was added.
- **Q6 synchronous versus queued admission:** deferred to A3. A1 exposes the
  synchronous admission capability required by the recommended option but does
  not publish or settle the HTTP behavior.

## Found wrong or inconsistent

These were reported, not corrected in authority documents:

1. `docs/SYSTEM_REFERENCE.md` §2 says **twelve Gradle modules** and its map says
   **five shared primitives**. `settings.gradle.kts` registers **thirteen modules**
   and includes six platform primitives, matching `docs/REPOSITORY_GUIDE.md`.
2. `docs/REPOSITORY_GUIDE.md` §3 says the repository has **no git remote**. This
   checkout has `origin`; pre-flight successfully fetched it and verified
   `origin/develop`. The adjacent claim that CI has never run cannot be established
   from this local checkout and remains unresolved.

## Explicit remaining work

- **Track A:** A2 dispatch queue, A3 contract/controllers and event-type registry,
  A4 platform-owned terminal lifecycle/replay, A5 remaining operator/event-type
  surface.
- **Track B:** B1–B3 are untouched.
- No partner `/submit`, bulk submit, callback, query, claim, status, replay or
  event-type endpoint exists on this branch.
- No migration was allocated from the Stream 1 ranges.

## Next slice

Independent review must rerun the focused A1 suite and risk-proportionate full/live
gates against `2f40cee`. After review and merge into `develop`, Developer 4 may
start Track B from refreshed `origin/develop`. Track A's next branch is proposed as
`feature/stream-1-a2`, also from refreshed `origin/develop`; it must not be based on
this unmerged feature branch.
