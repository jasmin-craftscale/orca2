# Lane operations implementation report

## What was built

Work was performed on `feature/lane-operations`, contract first in every package.
No database migration was added, and the `integration`, `readmodel`, and `notify`
modules were not changed.

### Package A — current visit on a lane

Added `GET /api/v1/lanes/{laneExternalId}/visit` to the runtime OpenAPI contract,
regenerated `VisitsApi`, and implemented it in `VisitController`. The read follows
the scope seam, resolves only an `ACTIVE` root visit, returns its current Flowable
activity, returns 422 for an unpublished lane, and represents a clear lane as an
empty result.

The contract-first compile failed as intended because `VisitController` did not yet
implement generated `getLaneVisit(String)`. Commit: `f738116`.

### Package B — take the next work item on a lane

Added `POST /api/v1/lanes/{laneExternalId}/take-next`. `WorkItemService` resolves
the lane's active execution through the `LaneVisitPort` interface, selects the
oldest queued item, and delegates the actual claim to the existing guarded
`take(...)` operation. The claim therefore retains its existing atomic update and
audit behaviour.

The execution side is implemented by the engine-free `LaneVisitLookup`, which uses
only `VisitReadRepository` and `AdmissionRepository`. The port owns its public
`LaneNotPublishedException`; no execution-domain type leaks across the module wall.

The contract-first compile failed as intended because `WorkItemController` did not
yet implement generated `takeNextOnLane(String)`. Commit: `6259e98`.

### Package C — abort one named visit

Added `POST /api/v1/visits/{visitExternalId}/abort`, reusing
`LaneResetEnvelope`. `LaneResetService.abort(...)` resolves the named visit, locks
its lane, and re-checks under that lock that the same visit is still active. In one
transaction it fails open work items, terminates the process, marks the visit
`FAILED`, and releases the lane. It returns 404 for an unknown visit and 409 when
the visit is no longer the lane's active visit.

The contract-first compile failed as intended because `VisitController` did not yet
implement generated `abortVisit(String)`.

## Verification

### Baseline

After stopping all running services, the corrected baseline was:

```text
integrationTest: suites=31 tests=229 failures=0
```

### Section 7 results

| Row | Real result |
|---:|---|
| 0 | `lsof -nP -iTCP -sTCP:LISTEN | grep -E ':(1808[1-6]|9100)\\b'` printed nothing before the suite. |
| 1 | `./gradlew build` → `BUILD SUCCESSFUL in 3s`; `68 actionable tasks: 7 executed, 61 up-to-date`. |
| 2 | After stopping Gradle daemons/workers to remove machine contention, the verified uncached run was `BUILD SUCCESSFUL in 5m 55s`; `integrationTest: suites=31 tests=236 failures=0`. Count: `229 → 236`. |
| 3 | `sendPlate` acknowledged `T-CHECK-01`; SQL returned `vis-b0dd102c-6b30-44b0-929e-39e10fa125da COMPLETED T-CHECK-01`. |
| 4 | `GET .../lanes/LANE-DEMO-01/visit` → `HTTP 200`; data contained `currentActivity:"manualInput"`, visit `vis-a2081c25-f627-493c-9918-3acdd03d480d`, and `status:"ACTIVE"`. |
| 5 | After abort, the same GET → `HTTP 200` and `{"status":"SUCCESS","code":"OK","errors":[],...}`. The serializer omitted `data`; it did not emit the plan's expected `"data":null`. |
| 6 | Unknown lane GET → `HTTP 422`, code `LANE_NOT_AT_THIS_INSTALLATION`. |
| 7 | Lane-visit GET without a token → `HTTP 401`, code `UNAUTHENTICATED`. |
| 8 | Take-next → `HTTP 200`; item `wi-dbd273f5-8d51-4cfc-a07d-352452df8348`, `status:"IN_PROGRESS"`, `assignee:"usr-demo-clerk"`. |
| 9 | Audit GET → `HTTP 200`; data contained `action:"TAKE"`, `actor:"usr-demo-clerk"`. |
| 10 | Immediate repeat take-next → `HTTP 404`, code `WORK_ITEM_NOT_FOUND`. |
| 11 | Take-next on `LANE-NOPE` → `HTTP 422`, code `LANE_NOT_AT_THIS_INSTALLATION`. |
| 12 | Abort visit A → `HTTP 200`, `failedWorkItems:1`; SQL returned visit `FAILED` and item `FAILED`. |
| 13 | Repeat abort A → `HTTP 409`, code `VISIT_NOT_ABORTABLE`; SQL still returned the item as `FAILED`. |
| 13b | After parking B, abort A → `HTTP 409`; SQL returned B as `ACTIVE`, its item as `QUEUED`, and B as the lane's active root. |
| 14 | Abort `vis-nope` → `HTTP 404`, code `VISIT_NOT_FOUND`. |
| 15 | The post-abort truck was admitted as `vis-e74b18bd-7439-48c1-84bc-ec1a13cd52ef ACTIVE T-VERIFY-B`. |
| 16 | All package mutations were run and restored; exact observations are below. |
| 17 | After an explicit final restart: `18081 200`, `18082 200`, `18083 200`. |

The final route check was:

```text
tos 200 APPROVED
```

The first row-2 attempt was not treated as a code failure or hidden. Under machine
contention from 19 containers, two SQL Servers, two Keycloaks, 12 Gradle daemons,
and nine orphaned `GradleWorkerMain` processes, `AdmissionThroughHttpIT` started its
random-port server but five requests received no HTTP headers:

```text
77 tests completed, 5 failed
ResourceAccessException: HTTP/1.1 header parser received no bytes
Caused by: java.io.EOFException: EOF reached while reading
BUILD FAILED in 5m 30s
```

After `./gradlew --stop` removed that contention, the same uncommitted code produced
the green row-2 result above. Before future full-suite runs, use:

```text
./gradlew --stop
ps aux | grep -c "[G]radleWorkerMain"    # expect 0
```

## Watch-it-fail evidence

### Package A

Changed the active-visit predicate from `status = 'ACTIVE'` to
`status IS NOT NULL`. `VisitReadPropertiesIT.laneCurrentVisit` failed at the
completed-visit assertion:

```text
a finished visit is not the lane's current visit — a clear lane must read as clear
Expecting an empty Optional, but it contained the COMPLETED visit.
```

The `status = 'ACTIVE'` predicate was restored and the test passed.

### Package B

Replaced delegation to the existing guarded `take(...)` operation with a direct
repository take and ignored the conditional-update result. The two-operator race
failed as required:

```text
the delegated conditional UPDATE admits exactly one lane claimant
expected: 1
 but was: 2
```

Delegation was restored and both package-B tests passed.

### Package C

The plan originally prescribed only a double abort. Deleting the identity clause
left that test green because the successful first abort had already emptied the
lane. Work stopped at that point; the test was not silently accepted. The corrected
matrix has one property per clause plus the happy path.

Deleting only `active.isEmpty() ||` made the empty-lane property fail:

```text
Expecting actual throwable to be an instance of:
  LaneResetService.VisitNotAbortableException
but was:
  java.util.NoSuchElementException: No value present
```

Deleting only `|| !active.get().externalId().equals(visitExternalId)` made the
superseded-visit property fail:

```text
Expecting code to raise a throwable.
```

With the identity clause absent, the double-abort property still passed:

```text
BUILD SUCCESSFUL in 15s
24 actionable tasks: 24 executed
```

Both clauses were restored. All three abort properties then passed.

## Decisions the plan did not dictate

- **Oldest-first in package B.** When a visit has several queued items, take-next
  chooses the minimum `queuedAt`. This gives the operator the item that has waited
  longest and keeps SLA order intuitive. The existing `take(...)` method remains
  the only claim writer.
- **`VisitReadRepository.VisitRow` ripple.** `executionId` was added as the first
  record component; `execution_id` was added to `COLUMNS`, and the single row mapper
  was updated. There were no external constructor call sites. Existing consumers
  use named accessors and needed no change; `LaneVisitLookup` is the new consumer of
  `executionId()`.
- **Abort guard coverage.** The superseded-visit property was added only after the
  prescribed mutation proved the original double-abort property could not execute
  the identity clause. It asserts the replacement visit, work item, and lane binding
  independently.

## Things found wrong

- The original package-B wiring registered the same `LaneVisitPort` twice. Returning
  `VisitQueryService` from another bean created two candidates.
- Moving the port onto `VisitQueryService` created an eager dependency cycle through
  the process engine. `@Lazy` would only have hidden the design error. The corrected
  engine-free `LaneVisitLookup` has exactly the two repositories it needs.
- The original port javadoc exposed an execution-domain exception across the module
  wall. The exception now belongs to `LaneVisitPort`, following `ManualStepPort`'s
  existing precedent.
- The original package-C double-abort test did not execute the identity comparison.
  Deleting that clause and observing a green test proved the test was insufficient;
  the corrected plan and implementation now test both sides of the compound guard.
- A clear-lane response omits `data` instead of serializing `"data": null`, although
  the controller calls `.data(null)` and the plan expects an explicit null. This was
  reported and not worked around.
- Live row 9 returned `elapsedSec:7227` immediately after the claim, while its
  `queuedAt`/`occurredAt` values were about two hours behind the visit/work-item
  `startedAt` values. This looks like a pre-existing timestamp/time-zone
  inconsistency in work-item audit output; it was observed, not changed.

## Not built

No migration, new table, read-model endpoint, notification behaviour, or changes to
the separately owned `integration`, `readmodel`, or `notify` modules were made.
