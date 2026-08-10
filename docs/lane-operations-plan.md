# Implementation Plan — Lane operations: see and control one lane

**For the developer (or AI) implementing this · August 2026 · Companion report: `docs/lane-operations-report.md`**

**This plan is prescriptive.** Unlike the stream plans, it does not leave design
choices open. Where a decision has already been made, it is stated as an
instruction, not an option. If you find yourself about to make a judgement call
that is not written here, that is a signal to stop and ask — not to decide.

**You are adding three endpoints.** No new database table. No migration. No change
to any existing behaviour.

---

## 0 · Before you write anything

### 0.1 · Get it running first

Do not start reading code. Get the system running and drive a truck through it —
it takes about twenty minutes and everything below will make sense afterwards.

Follow `docs/LOCAL_DEVELOPMENT.md` end to end. You are finished with this step when
`./gradlew sendPlate -Pplate=T-YOURNAME-01` puts a row in the database with status
`COMPLETED`.

### 0.2 · Read these, in this order

1. **`AGENTS.md`** (repository root) — the rules that fail the build. Not advisory.
2. **`docs/CODE_PATTERNS.md`** — §1 (a request end to end) and §2 (never check, then act).
3. **This plan, in full**, including §6, which lists ten traps that have already
   cost somebody a day each.

### 0.3 · The one file to copy from

**`services/orca-runtime/src/main/java/com/lynxis/orca/runtime/execution/` contains
a worked example of exactly what you are about to build** — the visit read surface,
added the same way, with the same layers:

| Layer | File |
|---|---|
| Contract | `services/orca-runtime/src/main/resources/openapi/orca-runtime.yaml` (the `/api/v1/visits` paths) |
| Controller | `execution/api/VisitController.java` |
| Service | `execution/domain/VisitQueryService.java` |
| Repository | `execution/persistence/VisitReadRepository.java` |
| Wiring | `execution/ExecutionConfiguration.java` (`visitController`, `visitQueryService`, `visitReadRepository` beans) |
| Tests | `src/integrationTest/java/.../execution/VisitReadPropertiesIT.java` |

**Read all six before starting.** Your work should look like a sibling of them.

---

## 1 · What you are building

Three endpoints that let an operator see and act on **one lane**.

| # | Endpoint | In one sentence |
|---|---|---|
| **A** | `GET /api/v1/lanes/{laneExternalId}/visit` | What is happening on this lane right now |
| **B** | `POST /api/v1/lanes/{laneExternalId}/take-next` | Claim the oldest queued work item on this lane's running visit |
| **C** | `POST /api/v1/visits/{visitExternalId}/abort` | Abort one visit, releasing its lane |

**What you are NOT building** — if you find yourself doing any of these, stop:

- No new table, no migration, no change to `db/migration/`.
- No change to the BPMN process file.
- No new build check.
- Nothing in the `integration`, `readmodel` or `notify` modules — **other developers
  own those right now and you will collide with them.**
- No frontend.

---

## 2 · Ground rules

| Rule | What it means for you |
|---|---|
| **Contract first, always** | Edit the OpenAPI document, regenerate, then make the controller satisfy the generated interface. Never write a controller method and then describe it in the contract |
| **Every database read goes through the scope seam** | You will never write `JdbcTemplate`. A build check fails if you do |
| **Scope comes from configuration, never from the request** | The site identifier is injected from config. A caller never names a site |
| **Tests prove properties, not paths** | "The endpoint returns a row" is not a test. "Another site's lane returns nothing" is |
| **Watch every new test fail before you trust it** | Break the thing on purpose, see the test catch it, put it back. A test you have never seen fail may not be wired to anything |
| **If it is not in this plan, ask** | Do not invent behaviour. A question asked costs an hour; a wrong guess costs a week |

---

## 3 · Work package A — `GET /lanes/{id}/visit`

### A.1 What it does

Given a lane's external identifier, return the **running visit on that lane**, with
the step the engine is parked at. If no visit is running, return `200` with `data:
null` — an empty lane is a normal answer, not an error.

### A.2 The contract

Add to `services/orca-runtime/src/main/resources/openapi/orca-runtime.yaml`, in the
`paths:` section, next to the existing `/api/v1/visits/{visitExternalId}`:

```yaml
  /api/v1/lanes/{laneExternalId}/visit:
    get:
      tags: [visits]
      operationId: getLaneVisit
      summary: The visit running on this lane right now, if any
      description: |
        The lane's current visit, with the step the engine is parked at.

        **An empty lane answers `200` with a null `data`, not `404`.** A lane with
        no truck at it is the normal state of a gate, and reporting it as "not
        found" would make an ordinary condition indistinguishable from a
        misconfigured lane identifier.

        A lane this installation does not publish answers `422` — that IS a
        configuration fault and must not read as "no truck here".
      parameters:
        - name: laneExternalId
          in: path
          required: true
          schema:
            type: string
            maxLength: 64
      responses:
        "200":
          description: The running visit, or null when the lane is clear
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/VisitEnvelope"
        "401":
          description: No credential, or one that did not verify
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
        "422":
          description: The lane is not published for this installation
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
```

Then run `./gradlew :services:orca-runtime:compileJava`. **It will fail**, because
`VisitController` now does not implement the new method. That failure is the
contract-first mechanism working. Do not work around it — implement the method.

### A.3 The code

**Repository** — add ONE method to `execution/persistence/VisitReadRepository.java`:

```java
	/**
	 * The running root visit on a lane, if there is one.
	 *
	 * <p>Root-only and {@code ACTIVE}: a child execution is a step inside a visit
	 * rather than a visit of its own, and the same filtered unique index that
	 * enforces one active root per lane is what makes "the" visit a meaningful
	 * phrase here.
	 */
	public Optional<VisitRow> activeOnLane(long laneId) {
		return seam.select(ScopedSelect.from("execution")
								.columns(COLUMNS)
								.scopedBy(SCOPE_COLUMN)
								.where(ROOT_ONLY + " AND lane_id = ? AND status = 'ACTIVE'", laneId),
						VisitReadRepository::map)
				.stream().findFirst();
	}
```

**Service** — add ONE method to `execution/domain/VisitQueryService.java`:

```java
	/**
	 * The visit running on a lane, with the engine's live position.
	 *
	 * @throws AdmissionService.LaneNotAtThisInstallationException when the lane is
	 *         not one this installation publishes — refused rather than answered
	 *         "no visit", because those are different facts and an operator acts on
	 *         them differently
	 */
	public Optional<VisitView> onLane(String laneExternalId) {
		long laneId = lanes.laneIdOf(laneExternalId)
				.orElseThrow(() -> new AdmissionService.LaneNotAtThisInstallationException(laneExternalId));

		return visits.activeOnLane(laneId)
				.map(row -> view(row, laneExternalId, livePositionOf(row)));
	}
```

⚠️ `view(...)` and `livePositionOf(...)` are **private static / private** in that
class already. `view` takes `(row, laneExternalId, currentActivity)`. Do not change
their signatures.

**Controller** — add the generated method to `execution/api/VisitController.java`:

```java
	@Override
	public ResponseEntity<VisitEnvelope> getLaneVisit(String laneExternalId) {
		Optional<VisitView> visit;
		try {
			visit = inScope(() -> visits.onLane(laneExternalId));
		}
		catch (AdmissionService.LaneNotAtThisInstallationException unknownLane) {
			throw new ApiException(ExecutionErrorCode.LANE_NOT_AT_THIS_INSTALLATION,
					unknownLane.getMessage());
		}

		return ResponseEntity.ok(new VisitEnvelope()
				.status(ApiStatus.SUCCESS)
				.code(ApiResponse.OK)
				.requestId(RequestId.current())
				.data(visit.map(VisitController::toModel).orElse(null)));
	}
```

⚠️ `toModel` is already `private static` in that class. `inScope` is already there.
`ExecutionErrorCode.LANE_NOT_AT_THIS_INSTALLATION` already exists — do not add a
new error code for this.

**Wiring** — nothing to do. `VisitController` and `VisitQueryService` already have
`@Bean` methods in `ExecutionConfiguration`. You added methods, not classes.

### A.4 Done when

- `GET /api/v1/lanes/LANE-DEMO-01/visit` returns the running visit with a
  `currentActivity` while a truck is parked at the manual step.
- The same call on a clear lane returns `200` with `"data": null`.
- `GET /api/v1/lanes/LANE-NOPE/visit` returns `422`.
- The property tests in A.5 pass, and you have watched each one fail.

### A.5 Tests

Add to `src/integrationTest/java/.../execution/VisitReadPropertiesIT.java`:

```java
	@Test
	@DisplayName("a lane's current visit is the ACTIVE root one, and a clear lane answers empty")
	void laneCurrentVisit() {
		insertVisit("vis-lane-done", OURS, 9L, "COMPLETED", Instant.now(), Instant.now());
		assertThat(asSite(OURS, () -> visits.activeOnLane(9L)))
				.as("a finished visit is not the lane's current visit — a clear lane must read as clear")
				.isEmpty();

		insertVisit("vis-lane-live", OURS, 9L, "ACTIVE", Instant.now(), null);
		assertThat(asSite(OURS, () -> visits.activeOnLane(9L)))
				.as("the ACTIVE root visit is the lane's current visit")
				.isPresent();
	}

	@Test
	@DisplayName("one site cannot read another site's lane")
	void laneCurrentVisitIsScoped() {
		insertVisit("vis-theirs-lane", THEIRS, 11L, "ACTIVE", Instant.now(), null);

		assertThat(asSite(OURS, () -> visits.activeOnLane(11L)))
				.as("the seam applies the site condition before the filter, so another site's "
						+ "lane is never selected rather than selected and refused")
				.isEmpty();
	}
```

**How to watch these fail:** temporarily change `status = 'ACTIVE'` to `status IS
NOT NULL` in `activeOnLane` — the first test must fail. Put it back.

---

## 4 · Work package B — `POST /lanes/{id}/take-next`

### B.1 What it does

An operator at a lane monitor clicks "take next". Claim **the oldest queued work
item on that lane's running visit** and return it, exactly as
`POST /work-items/{id}/take` would.

**This is a convenience over the existing claim, not a new kind of claim.** Every
rule that governs `take` governs this: eligibility is checked first, the conditional
UPDATE is the only race guard, the loser gets a typed `409`, and the action is
audited as `TAKE`.

### B.2 The rules, exactly

Follow these in order. Each has a reason; none is optional.

1. **Resolve the lane.** Not published by this installation → `422`
   (`LANE_NOT_AT_THIS_INSTALLATION`).
2. **Find the lane's running visit.** No `ACTIVE` root visit → `404`
   (`WORK_ITEM_NOT_FOUND`, message: nothing is running on this lane). ⚠️ Do **not**
   invent a new error code for this.
3. **Find its open work items**, and keep only those with status `QUEUED`.
   `IN_PROGRESS` items belong to somebody already.
4. **Pick the oldest by `queuedAt`.** ⚠️ **Oldest-first, NOT the priority ordering
   the queue grid uses.** Reason: take-by-lane answers "the next thing at *this*
   lane", where a single visit's items are a sequence rather than a ranked queue.
   The grid ranks across lanes; this does not. **State this in your report.**
5. **No queued item** → `404` (`WORK_ITEM_NOT_FOUND`).
6. **Claim it by calling the existing `WorkItemService.take(externalId, actor)`.**
   ⚠️ **Do not write a second claim.** That method already checks eligibility,
   performs the guarded update, writes the audit row and throws the typed conflict.
   Duplicating it is how two claim paths drift apart.
7. **Two operators racing on the same lane**: both may select the same item at
   step 4; exactly one wins the guarded update inside `take`, and the loser
   receives the `409` that method already throws. **That is correct and you must
   not try to prevent it** — the conditional UPDATE is the guard, and a pre-check
   would be the race, not the fix.

### B.3 The contract

```yaml
  /api/v1/lanes/{laneExternalId}/take-next:
    post:
      tags: [workItems]
      operationId: takeNextOnLane
      summary: Claim the oldest queued item on this lane's running visit
      description: |
        Take-by-lane, for an operator working a lane monitor rather than the queue.

        A convenience over `POST /work-items/{id}/take` and nothing more: the same
        eligibility check, the same conditional UPDATE as the only race guard, the
        same typed `409` for the loser, the same `TAKE` audit row.

        **Oldest queued first, not the queue grid's priority order.** The grid ranks
        work across lanes; this answers "the next thing at this lane", where one
        visit's items are a sequence.
      parameters:
        - name: laneExternalId
          in: path
          required: true
          schema:
            type: string
            maxLength: 64
      responses:
        "200":
          description: The item, now held by the caller
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/WorkItemEnvelope"
        "401":
          description: No credential, or no resolvable operator behind it
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
        "403":
          description: The operator is outside the item's eligible teams
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
        "404":
          description: Nothing is running on this lane, or it has no queued item
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
        "409":
          description: Somebody else claimed it first
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
        "422":
          description: The lane is not published for this installation
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
```

### B.4 Where the code goes

⚠️ **This one crosses a module wall, and the wall is enforced by a build check.**

`workitem` may not reach into `execution.domain` or `execution.persistence`. But
finding a lane's running visit is `execution`'s knowledge. **`workitem` must not
look it up itself.**

**The rule: put the new endpoint in `workitem`, and get the visit's `executionId`
from `execution` through a port.** `execution/api/ManualStepPort.java` is the
worked example of exactly this shape — an interface in the callee's `api` package,
implemented inside the callee, consumed by the caller.

Concretely:

1. **Add to `execution/api/`** a small interface, e.g. `LaneVisitPort`:

```java
package com.lynxis.orca.runtime.execution.api;

import java.util.Optional;

/**
 * The seam through which {@code workitem} learns which visit is running on a lane.
 *
 * <p>Finding a lane's active visit is {@code execution}'s knowledge, and the module
 * wall forbids {@code workitem} from reading its tables. This interface is the
 * narrow, deliberate crossing — the same shape as {@code ManualStepPort} in the
 * other direction.
 */
public interface LaneVisitPort {

	/**
	 * The execution id of the visit running on this lane.
	 *
	 * @return empty when the lane is clear
	 * @throws com.lynxis.orca.runtime.execution.domain.AdmissionService.LaneNotAtThisInstallationException
	 *         when this installation does not publish the lane
	 */
	Optional<Long> activeVisitOn(String laneExternalId);
}
```

2. **Implement it in `execution`** (a small class in `execution/domain/`, or extend
   the existing `VisitQueryService` to implement it — either is acceptable; say
   which you chose and why in the report). Register it as a `@Bean` in
   `ExecutionConfiguration`.

3. **Consume it in `workitem`** — add a method to `WorkItemService`:

```java
	/**
	 * Take-by-lane: the oldest queued item on the lane's running visit.
	 *
	 * <p>Delegates the claim to {@link #take}; this method only decides WHICH item.
	 * A second claim implementation is how two paths drift apart, so there is not one.
	 */
	public WorkItem takeNextOnLane(String laneExternalId, String actor) {
		long executionId = laneVisits.activeVisitOn(laneExternalId)
				.orElseThrow(() -> new NothingToTakeOnLaneException(laneExternalId,
						"no visit is running on it"));

		WorkItem next = repository.openItemsOf(executionId).stream()
				.filter(item -> WorkItem.QUEUED.equals(item.status()))
				.min(java.util.Comparator.comparing(WorkItem::queuedAt))
				.orElseThrow(() -> new NothingToTakeOnLaneException(laneExternalId,
						"its running visit has no queued work item"));

		return take(next.externalId(), actor);
	}
```

**These names are verified and you may copy them as written:** `WorkItem.QUEUED` is a
constant inside the `WorkItem` record in `workitem/domain/WorkItemTables.java`, and
`queuedAt()` is one of its components.

⚠️ **`WorkItemNotFoundException` is the wrong exception here, and this is worth
understanding rather than working around.** Its only constructor takes an *external
id* and wraps it in a fixed sentence — *"No work item 'X' exists under this
installation's scope."* Passing it a sentence produces *"No work item 'no visit is
running on lane LANE-3' exists…"*, which is gibberish in an operator's face.

**Add a small sibling exception** next to it in `WorkItemService`, and map it to
`WORK_ITEM_NOT_FOUND` (404) in the controller — the HTTP answer is the same, the
message is not:

```java
	/**
	 * There is nothing on this lane for the caller to take.
	 *
	 * <p>Distinct from {@link WorkItemNotFoundException}, which is about an item the
	 * caller named. Here the caller named a LANE and the answer is about the lane's
	 * state, so the message has to say which of the two reasons applies — an
	 * operator who is told "not found" about an item they never mentioned will go
	 * looking for the wrong fault.
	 */
	public static class NothingToTakeOnLaneException extends RuntimeException {

		public NothingToTakeOnLaneException(String laneExternalId, String because) {
			super("Nothing to take on lane '" + laneExternalId + "': " + because + ".");
		}
	}
```

4. **Controller** — add the method to `workitem/api/WorkItemController.java`,
   following how `takeWorkItem` there resolves the operator and maps exceptions.

### B.5 Done when

- A truck parked at the manual step, then `POST /api/v1/lanes/LANE-DEMO-01/take-next`
  returns the item as `IN_PROGRESS` with the caller as assignee, and
  `GET /work-items/{id}/audit` shows a `TAKE` row.
- A clear lane returns `404`. An unpublished lane returns `422`.
- The concurrency property in B.6 holds.

### B.6 Tests

Add to `src/integrationTest/java/.../workitem/WorkItemLifecycleIT.java` (it already
has the harness for creating parked visits and work items — **reuse it, do not
build a second one**):

```java
	@Test
	@DisplayName("two operators taking next on the same lane: exactly one wins, and the loser is told")
	void takeNextOnLaneHasOneWinner() {
		// Reuse this suite's existing helper to park a visit with ONE queued item.
		// Then race two callers at takeNextOnLane and assert exactly one WorkItem
		// came back and the other saw the typed conflict — never a silent no-op,
		// and never two winners.
	}
```

⚠️ Model it on the existing test in that file that races two operators on `take`.
**Read that test and copy its threading shape** rather than inventing one.

**How to watch it fail:** temporarily change `take(next.externalId(), actor)` to
skip the guard (call the repository's update without checking its boolean) — the
race test must fail. Put it back.

---

## 5 · Work package C — `POST /visits/{id}/abort`

### C.1 What it does

Abort **one visit** by its own identifier: terminate its process instance, fail its
open work items, and free its lane — in one transaction.

### C.2 The critical instruction

⚠️ **`LaneResetService.reset(laneExternalId, actor)` already does exactly this**,
addressed by lane instead of by visit. It is in
`execution/domain/LaneResetService.java` and it returns a
`LaneReset(laneExternalId, visitExternalId, failedWorkItems)` record.

**Your job is to add a visit-addressed entry point to that existing service — NOT
to write a second abort.** Two implementations of "tear a visit down" that drift
apart is precisely the class of defect this codebase spends build checks
preventing.

The simplest correct shape: resolve the visit's lane, then delegate to the existing
reset. If the visit is not `ACTIVE`, answer `409` — aborting a finished visit is not
an ordinary outcome the way resetting a clear lane is, because the caller named a
specific visit and was wrong about its state.

### C.3 The contract

```yaml
  /api/v1/visits/{visitExternalId}/abort:
    post:
      tags: [visits]
      operationId: abortVisit
      summary: Abort this visit — terminate its process, fail its work items, free its lane
      description: |
        One transaction, and the same one lane reset performs: the process instance
        is terminated, the visit's open work items are failed, and the lane is
        released for the next truck.

        Addressed by visit rather than by lane. Aborting a visit that is no longer
        running answers `409` — unlike resetting an already-clear lane, which is an
        ordinary outcome, naming a specific visit and being wrong about its state
        is a mistake worth reporting.
      parameters:
        - name: visitExternalId
          in: path
          required: true
          schema:
            type: string
            maxLength: 64
      responses:
        "200":
          description: What was aborted
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LaneResetEnvelope"
        "401":
          description: No credential, or no resolvable operator behind it
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
        "404":
          description: No such visit under this installation's scope
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
        "409":
          description: The visit is no longer running
          content:
            application/json:
              schema:
                $ref: "../../../../../../platform/web/src/main/resources/openapi/_shared.yaml#/components/schemas/ApiResponse"
```

⚠️ Reuse `LaneResetEnvelope` — do not define a new response schema for the same
data.

### C.4 Done when

- Park a truck at the manual step, `POST /api/v1/visits/{id}/abort`, and observe:
  the visit is `FAILED`, its work item is `FAILED`, the lane accepts the next truck.
- Aborting the same visit again returns `409`.
- An unknown visit returns `404`.

### C.5 Tests

Add to `VisitReadPropertiesIT` or the lane-reset suite (**whichever already has the
harness** — do not create a third):

- Aborting a running visit fails its open work items **and** frees the lane.
- Aborting a visit that is not `ACTIVE` returns the typed conflict and **changes
  nothing** — assert the work item's status is unchanged afterwards.

---

## 6 · ⚠️ Ten traps, each of which has already cost somebody a day

**Read this section before you write code, not after the build breaks.**

1. **The scope seam refuses JOINs.** `ScopedSelect.from("a JOIN b")` throws at
   runtime — table names are allow-listed as `name` or `schema.name` only. One table
   per statement. Need data from two? Two reads, joined in Java.
2. **Column names must be bare.** `.columns("e.external_id")` is refused for the
   same reason. Use `"external_id"`.
3. **A `@RestController` that takes a config value needs an explicit `@Bean`
   method.** Component scanning finds the class and then cannot construct it —
   there is no bean of type `String` to autowire. **The symptom is every single
   integration suite in the service failing at once with a context-load error**,
   which looks catastrophic and is a two-line fix. See `visitController` in
   `ExecutionConfiguration`.
4. **`ScopeContext.callIn` takes a `Callable`, not a `Supplier`.** It will not
   compile with a `Supplier` and the error is unhelpful.
5. **Generated `StatusEnum.fromValue()` THROWS on an unknown value** — it does not
   return null. Using it as a validity test turns a caller's typo into a `500`.
   Compare against `values()` instead.
6. **Bean validation enforces `minimum`/`maximum` but NOT `enum` membership.** A
   declared enum on a query parameter is documentation, not a check. If an invalid
   value must be refused, refuse it in the controller.
7. **Resolve lookups once per page, never once per row.** Returning a list and
   calling a lookup inside `.map()` is an N+1 — measured at 67 queries for 66 rows
   in this exact codebase before it was fixed. `VisitQueryService.search` shows the
   batched shape.
8. **Read timestamps as UTC explicitly** —
   `rs.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone("UTC")))`.
   Plain `getTimestamp` applies the JVM's zone to a UTC value: invisible on a UTC
   machine, wrong everywhere else.
9. **`./gradlew check integrationTest` will lie to you without `--rerun-tasks`.** It
   answers from cache in under a second and reports a success it did not run.
10. **`core.topology_lane` does not exist in a bare test schema.** It is a view core
    publishes. A lightweight repository test that migrates only `runtime` cannot
    resolve lane identifiers — test that behaviour at the service layer with a stub
    instead. `VisitReadPropertiesIT`'s `CountingLanes` shows how.

---

## 7 · Verification — run these and keep the output

| # | Command / check | Expected |
|---|---|---|
| 1 | `./gradlew build` | Green from a clean tree |
| 2 | `./gradlew check integrationTest --rerun-tasks` | Green. **`--rerun-tasks` is not optional** — see trap 9 |
| 3 | `./gradlew sendPlate -Pplate=T-CHECK-01` | Visit reaches `COMPLETED` — the gate path is unbroken |
| 4 | `GET /lanes/{id}/visit` on a busy lane | The visit, with `currentActivity` |
| 5 | `GET /lanes/{id}/visit` on a clear lane | `200`, `"data": null` |
| 6 | `GET /lanes/LANE-NOPE/visit` | `422` |
| 7 | `POST /lanes/{id}/take-next` after parking a truck | `200`, item `IN_PROGRESS`, audit shows `TAKE` |
| 8 | `POST /lanes/{id}/take-next` on a clear lane | `404` |
| 9 | `POST /visits/{id}/abort` on a running visit | Visit `FAILED`, item `FAILED`, lane free |
| 10 | `POST /visits/{id}/abort` again | `409`, nothing changed |
| 11 | **Every new test watched to fail** | Break it, see it caught, restore it. Record what you broke |
| 12 | All six services boot | A green suite does not prove a service starts |

**How to drive the live checks:** `docs/phase-1-demo.md` has the full sequence,
including how to force a truck into the manual branch (remap the connector route off
`200`) and how to get a Keycloak token. Restore the connector route afterwards.

---

## 8 · When you are stuck

| Situation | Do this |
|---|---|
| A build check fails and you do not understand why | **Read the failure message in full** — they are written to be self-contained and name the violator. Do not disable a check |
| You need data from another module's tables | You need a port in that module's `api` package. §4.4 is the worked example. **Never** reach into another module's `persistence` |
| This plan does not say what should happen in some case | **Ask.** Do not decide. Note it and continue with the parts that are specified |
| Something in this plan appears wrong | Say so, with evidence. This plan has been wrong before and reporting it is worth more than working around it |
| A test passes and you have not watched it fail | It is not finished |

---

## 9 · The report — `docs/lane-operations-report.md`

Short, and in this shape:

1. **What was built**, per work package — and anything that was not.
2. **The §7 table with real results**, including command output.
3. **Every decision this plan did not dictate** — at minimum: where you put the
   `LaneVisitPort` implementation and why, and how you shaped the abort entry point.
4. **What you broke to watch each test fail**, and that it was restored.
5. **Anything that looked wrong** in this plan, the code, or the documents —
   **reported, not silently corrected.**

---

*Start with A. It is the smallest and it teaches the shape the other two reuse.*
