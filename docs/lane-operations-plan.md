# Implementation Plan — Lane operations: see and control one lane

**Self-contained. Written to be executed autonomously and verified by the executor.**

**This plan is prescriptive.** Where a decision has already been made it is an
instruction, not an option. If you are about to make a judgement call that is not
written here, stop and ask — do not decide.

**Scope:** three endpoints. **No new table. No migration. No migration-number band
needed.** No change to existing behaviour.

**Where to work:** branch `feature/lane-operations` off `main`. Do not commit to
`main`. Do not touch the `integration`, `readmodel` or `notify` modules — other
developers own those right now.

---

## 0 · Environment — get here before writing any code

Every command below was executed against this repository. Run them in order. **If
any step does not produce what is described, stop and report it — do not continue.**

### ⚠️ 0.0 · The one rule that governs this whole section

**The integration suite and the running services use the SAME `runtime` schema on the
SAME database. They must never run at the same time.**

The suites migrate `runtime`, create visits and let the real engine advance them. A
running `orca-runtime` has an active Flowable async executor that will pick up *the
suite's* jobs, and a running `orca-edge` polls its buffer. The result is a failing
test that looks like a real defect and is not — the 8-lane admission property is the
one that fails first, with an unexplained `500`.

**So:**

| Doing this | Services must be |
|---|---|
| `./gradlew check integrationTest` (§0.9, §7.1) | **STOPPED** |
| Live endpoint checks (§0.7, §0.8, §7.3) | **RUNNING** |

Stop them with:

```bash
for p in 18081 18082 18083; do
  PID=$(lsof -nP -iTCP:$p -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$PID" ] && kill $PID
done
```

⚠️ **This bit the first run of this plan.** The baseline was executed with services
left running from an earlier session and reported `failures=1` on a suite that is
green. Check for stray listeners before you trust any red result:
`lsof -nP -iTCP -sTCP:LISTEN | grep -E ":(1808[1-6]|9100)\b"` must be empty before
you run the suite.

### 0.1 · Prerequisites

- Docker running
- A JDK (any recent one; the Java 25 toolchain auto-provisions)
- Python 3

### 0.2 · Start the stack

```bash
cd deploy
cp .env.example .env          # ONLY if deploy/.env does not already exist — never overwrite it
docker compose up -d
docker compose run --rm bootstrap
```

`bootstrap` creates seven schemas, seven logins and their grants. It is a no-op on a
database that already has them.

### 0.3 · Ports — read this before booting

The committed ports are `8081`–`8086` for services, `1433` SQL Server, `8080`
Keycloak, `9100` the camera listener.

**If those ports are free on your machine, use them and ignore the offsets below.**
If something else already holds them (the old ORCA system does), everything shifts
by `+10000` and the stack's own ports come from `deploy/.env`. Check first:

```bash
lsof -nP -iTCP -sTCP:LISTEN | grep -E ":(8081|8082|8083|1433|8080|9100)\b"
```

The rest of this plan writes the offset form (`18081`, `18082`, `18083`, `21433`,
`18080`). **Substitute your actual ports throughout.**

### 0.4 · Boot the three gate-path services

⚠️ **`bootRun` never returns.** It runs the service in the foreground until killed.
If you are an agent executing commands one after another, **run each of these in the
background** — otherwise the first one hangs and nothing after it happens. A human
uses three terminals; an agent appends `&`, or uses whatever backgrounding its tool
offers, and then polls the health check below.

**Order matters and the platform enforces it** — core publishes the views runtime and
edge wait for, and they refuse to start before it has migrated. Wait for core to
answer `200` before starting the other two.

```bash
export ORCA_DB_URL='jdbc:sqlserver://localhost:21433;databaseName=orca;encrypt=true;trustServerCertificate=true'
export ORCA_OIDC_ISSUER_URI='http://localhost:18080/realms/orca'

./gradlew bootRun -p services/orca-core    --args='--spring.profiles.active=local --server.port=18081'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local --server.port=18082 --orca.runtime.edge-base-url=http://localhost:18083'
./gradlew bootRun -p services/orca-edge    --args='--spring.profiles.active=local --server.port=18083 --orca.edge.runtime-base-url=http://localhost:18082'
```

⚠️ **`--spring.profiles.active=local` is not optional.** The committed inter-service
credential is recognised by name and the service refuses to start with it otherwise.
Do not weaken that check.

Confirm all three — poll rather than assume, because a service takes tens of seconds
to migrate and start:

```bash
for i in $(seq 1 40); do
  a=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:18081/actuator/health)
  [ "$a" = "200" ] && { echo "core UP"; break; }; sleep 5
done
for p in 18081 18082 18083; do curl -s -o /dev/null -w "$p %{http_code}\n" http://localhost:$p/actuator/health; done
```

Expect `200` from each.

⚠️ **Restart runtime after every code change** before running any live check in §7.3.
A running service holds the old classes; verifying against it proves nothing about
what you just wrote. Kill it with
`kill $(lsof -nP -iTCP:18082 -sTCP:LISTEN -t)` and start it again.

### 0.5 · Seed the demo site

```bash
cd deploy && docker compose run --rm demo-seed
```

One site (`SITE-DEMO`), one lane (`LANE-DEMO-01`), a camera, a barrier, the `tos`
connector, the routing row mapping `200`→`APPROVED`, plus the clerk world:
`usr-demo-clerk`, team `team-demo-clerks`, screen `scr-demo-manual`.

### 0.6 · A database shell

```bash
q(){ docker exec orca-sqlserver /opt/mssql-tools18/bin/sqlcmd \
  -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -h -1 -W -Q "SET NOCOUNT ON; $1"; }
```

⚠️ **Shell state does not survive between separately invoked commands.** If each of
your commands runs in a fresh shell, `q` and `$TOK` from an earlier step will not
exist. Either define `q` at the top of every command that uses it, or write the
`docker exec …` out in full each time. The same applies to the token in §0.7 —
re-fetch it in the same command that uses it.

⚠️ **The `-I` is required, not cosmetic.** Several tables carry filtered indexes and
SQL Server refuses to write to those unless `QUOTED_IDENTIFIER` is on. Without it a
write fails with an error naming SET options and no table at all. Reads work either
way, which is why it is easy to miss.

### 0.7 · Get a token — and link it to an operator

**⚠️ This step is mandatory for work packages B and C, and skipping it is the single
most likely way to lose an hour.** Those endpoints require a resolvable *operator*,
and the local Keycloak realm deliberately contains no human users. Without the link
below, every call returns `401 OPERATOR_UNRESOLVED` and the code is not at fault.

```bash
TOK=$(curl -s -X POST "http://localhost:18080/realms/orca/protocol/openid-connect/token" \
  -d "client_id=orca-core" -d "client_secret=local-dev-secret-orca-core" \
  -d "grant_type=client_credentials" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

SUB=$(echo "$TOK" | cut -d. -f2 | python3 -c "import sys,base64,json; s=sys.stdin.read().strip(); s+='='*(-len(s)%4); print(json.loads(base64.urlsafe_b64decode(s))['sub'])")

q "UPDATE core.user_account SET keycloak_subject = '$SUB' WHERE external_id = 'usr-demo-clerk'"
q "SELECT user_external_id, keycloak_subject FROM core.topology_operator"
```

The last query must show `usr-demo-clerk` with a non-null subject. This is the
operator directory doing its job, not a workaround.

⚠️ **The token expires in five minutes.** Re-run the first command whenever a call
starts returning `401`.

### 0.8 · Drive a truck, and learn to park one

Happy path — the visit reaches `COMPLETED`:

```bash
./gradlew sendPlate -Pplate=T-SETUP-01 -Pport=9100
q "SELECT TOP 1 external_id, status, plate FROM runtime.execution ORDER BY execution_id DESC"
```

**Parking a truck at the manual step** — you will need this for B and C. Unmap the
connector's success route so the answer matches no branch, and the process takes its
default flow to a human:

```bash
q "UPDATE runtime.connector_route SET http_status = 418 WHERE connector_name = 'tos'"
./gradlew sendPlate -Pplate=T-PARKED-01 -Pport=9100
sleep 8
q "SELECT TOP 1 external_id, status, plate FROM runtime.execution ORDER BY execution_id DESC"   -- ACTIVE
q "SELECT TOP 1 external_id, status FROM runtime.work_item ORDER BY work_item_id DESC"          -- QUEUED
```

⚠️ **Always restore it afterwards**, or every later truck parks:

```bash
q "UPDATE runtime.connector_route SET http_status = 200 WHERE connector_name = 'tos'"
```

### 0.9 · Establish the baseline — before you change anything

⚠️ **You cannot claim you ended green unless you know you started green.**

⚠️ **STOP THE SERVICES FIRST — see §0.0.** The suite and a running `orca-runtime`
share the `runtime` schema, and a red result from a contaminated run is
indistinguishable from a real defect:

```bash
for p in 18081 18082 18083; do
  PID=$(lsof -nP -iTCP:$p -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$PID" ] && kill $PID
done
sleep 8
lsof -nP -iTCP -sTCP:LISTEN | grep -E ":(1808[1-6]|9100)\b"   # must print NOTHING
```

Then:

```bash
git checkout -b feature/lane-operations
./gradlew check integrationTest --rerun-tasks
```

Then count what ran:

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
tot=f=0; n=0
for p in glob.glob('**/build/test-results/integrationTest/*.xml', recursive=True):
    r=ET.parse(p).getroot(); n+=1
    tot+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0))
print(f"BASELINE integrationTest: suites={n} tests={tot} failures={f}")
EOF
```

**Expected: `suites=31 tests=229 failures=0`.** Record this number — §7.1 compares
against it.

If the baseline is not green, **stop and report it.** Something is wrong with the
environment or the checkout, and anything you build on top will be uninterpretable.

### 0.10 · Read these

1. **`AGENTS.md`** (repository root) — the rules that fail the build.
2. **`docs/CODE_PATTERNS.md`** §1 and §2.
3. **This plan, in full — including §6, the traps.**

### 0.11 · The worked example to copy

**The visit read surface is the same shape you are about to build.** Read all six
files before starting; your work should look like their sibling.

| Layer | File |
|---|---|
| Contract | `services/orca-runtime/src/main/resources/openapi/orca-runtime.yaml` — the `/api/v1/visits` paths |
| Controller | `services/orca-runtime/src/main/java/com/lynxis/orca/runtime/execution/api/VisitController.java` |
| Service | `.../execution/domain/VisitQueryService.java` |
| Repository | `.../execution/persistence/VisitReadRepository.java` |
| Wiring | `.../execution/ExecutionConfiguration.java` — beans `visitController`, `visitQueryService`, `visitReadRepository` |
| Tests | `services/orca-runtime/src/integrationTest/java/com/lynxis/orca/runtime/execution/VisitReadPropertiesIT.java` |

---

## 1 · What you are building

| # | Endpoint | In one sentence |
|---|---|---|
| **A** | `GET /api/v1/lanes/{laneExternalId}/visit` | What is happening on this lane right now |
| **B** | `POST /api/v1/lanes/{laneExternalId}/take-next` | Claim the oldest queued work item on this lane's running visit |
| **C** | `POST /api/v1/visits/{visitExternalId}/abort` | Abort one visit, releasing its lane |

**Explicitly out of scope** — stop if you find yourself doing any of these: a new
table or migration; a change to the BPMN process; a new build check; anything in
`integration`, `readmodel` or `notify`; any frontend.

---

## 2 · Ground rules

| Rule | What it means here |
|---|---|
| **Contract first, always** | Edit the OpenAPI document, regenerate, then satisfy the generated interface. The build failing after a contract edit is the mechanism working |
| **Every read goes through the scope seam** | You will never write `JdbcTemplate`. A build check fails if you do |
| **Scope comes from configuration, never the request** | A caller never names a site |
| **Tests prove properties, not paths** | "The endpoint returns a row" is not a test. "Another site's lane returns nothing" is |
| **Watch every new test fail before trusting it** | Break the thing, see it caught, restore it. Record what you broke |
| **If it is not in this plan, ask** | Do not invent behaviour |

---

## 3 · Work package A — `GET /lanes/{id}/visit`

### A.1 Behaviour

Return the **running visit on the lane**, with the step the engine is parked at.

| Case | Answer |
|---|---|
| A visit is running | `200`, the visit, `currentActivity` populated |
| The lane is clear | `200`, `"data": null` |
| The lane is not published by this installation | `422` `LANE_NOT_AT_THIS_INSTALLATION` |
| No/invalid credential | `401` |

⚠️ **An empty lane is `200`, not `404`.** A lane with no truck is the normal state
of a gate; reporting it as "not found" makes an ordinary condition
indistinguishable from a mistyped lane identifier.

### A.2 Contract

Add to `services/orca-runtime/src/main/resources/openapi/orca-runtime.yaml` under
`paths:`, immediately after `/api/v1/visits/{visitExternalId}`:

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

Then:

```bash
./gradlew :services:orca-runtime:compileJava
```

**It must fail**, because `VisitController` does not yet implement `getLaneVisit`.
That failure is contract-first working. Implement the method; do not work around it.

### A.3 Code — three additions, no new classes

**1. Repository** — add to `execution/persistence/VisitReadRepository.java`:

```java
	/**
	 * The running root visit on a lane, if there is one.
	 *
	 * <p>Root-only and {@code ACTIVE}: a child execution is a step inside a visit
	 * rather than a visit of its own, and the filtered unique index that enforces one
	 * active root per lane is what makes "the" visit a meaningful phrase here.
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

`COLUMNS`, `SCOPE_COLUMN`, `ROOT_ONLY` and `map` already exist in that file.

**2. Service** — add to `execution/domain/VisitQueryService.java`:

```java
	/**
	 * The visit running on a lane, with the engine's live position.
	 *
	 * @throws AdmissionService.LaneNotAtThisInstallationException when this
	 *         installation does not publish the lane — refused rather than answered
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

`view(row, laneExternalId, currentActivity)` and `livePositionOf(row)` already exist
as private members. Do not change their signatures. Add the import for
`AdmissionService` if it is not present.

**3. Controller** — add to `execution/api/VisitController.java`:

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

`inScope` and `toModel` already exist. `ExecutionErrorCode.LANE_NOT_AT_THIS_INSTALLATION`
already exists — **do not add a new error code.**

**Wiring:** nothing. You added methods to classes that already have `@Bean` methods.

### A.4 Tests

Add to `VisitReadPropertiesIT`:

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

`insertVisit`, `asSite`, `OURS`, `THEIRS` and `visits` already exist in that class.

**Watch it fail:** change `status = 'ACTIVE'` to `status IS NOT NULL` in
`activeOnLane`. `laneCurrentVisit` must fail. Restore it.

### A.5 Done when

- The two tests pass and you have watched each fail.
- Live: a parked truck's lane returns the visit with `currentActivity`; a clear lane
  returns `"data": null`; `LANE-NOPE` returns `422`.

---

## 4 · Work package B — `POST /lanes/{id}/take-next`

### B.1 Behaviour

Claim **the oldest queued work item on that lane's running visit**, exactly as
`POST /work-items/{id}/take` would.

| Case | Answer |
|---|---|
| Claimed | `200`, the item `IN_PROGRESS`, caller is assignee, audit row `TAKE` |
| Lane not published | `422` `LANE_NOT_AT_THIS_INSTALLATION` |
| No visit running, or no queued item | `404` `WORK_ITEM_NOT_FOUND` |
| Operator outside the item's eligible teams | `403` `WORK_ITEM_NOT_ELIGIBLE` |
| Somebody claimed it first | `409` `WORK_ITEM_CONFLICT` |
| No resolvable operator | `401` `OPERATOR_UNRESOLVED` |

### B.2 The rules, in order

1. Resolve the lane. Not published → `422`.
2. Find the lane's `ACTIVE` root visit. None → `404`.
3. Take its open items; keep only `QUEUED`. `IN_PROGRESS` belongs to somebody.
4. **Pick the oldest by `queuedAt`.** ⚠️ **Oldest-first, NOT the priority ordering
   the queue grid uses.** The grid ranks work across lanes; this answers "the next
   thing at *this* lane", where one visit's items are a sequence. **Record this
   decision in your report.**
5. No queued item → `404`.
6. **Claim by calling the existing `WorkItemService.take(externalId, actor)`.**
   ⚠️ **Do not write a second claim.** That method already checks eligibility,
   performs the guarded update, writes the audit row and throws the typed conflict.
7. **Two operators racing the same lane may both select the same item at step 4.**
   Exactly one wins the conditional UPDATE inside `take`; the loser gets the `409`
   that method already throws. **That is correct — do not add a pre-check to prevent
   it.** A pre-check would be the race, not the fix.

### B.3 Contract

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

### B.4 Crossing the module wall — read before coding

⚠️ `workitem` may not reach `execution.domain` or `execution.persistence`. A build
check (`ModuleWallRule`) enforces it with three tests. But *which visit is running on
a lane* is `execution`'s knowledge.

**The rule: the endpoint lives in `workitem`, and it learns the visit through a port
published by `execution`.** `execution/api/ManualStepPort.java` is the worked example
of this exact shape in the other direction — read it first.

**Step 1** — create `execution/api/LaneVisitPort.java`:

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
	 * @throws LaneNotPublishedException when this installation does not publish the
	 *         lane — a different fact from "the lane is clear", and the caller
	 *         answers them differently
	 */
	Optional<Long> activeVisitOn(String laneExternalId) throws LaneNotPublishedException;

	/**
	 * This installation does not publish that lane.
	 *
	 * <p><strong>Declared here, on the port, and not reused from {@code execution}'s
	 * domain.</strong> An exception a caller must catch is part of the contract, so a
	 * port that throws a domain type has not hidden the module — the caller ends up
	 * importing {@code execution.domain} to write the catch clause, and
	 * {@code ModuleWallRule} refuses it. {@code ManualStepPort.ProcessNotWaitingException}
	 * is the same shape for the same reason.
	 */
	class LaneNotPublishedException extends RuntimeException {

		public LaneNotPublishedException(String laneExternalId) {
			super("Lane '" + laneExternalId + "' is not published by this installation.");
		}
	}
}
```

⚠️ **This is the whole reason the port exists, and it is easy to get wrong.** A port
that hides a module's *classes* but exposes its *exceptions* has not hidden the
module. Read `execution/api/ManualStepPort.java` — it nests its own exception, and
`WorkItemController` catches `ManualStepPort.ProcessNotWaitingException`, never a
domain type. Copy that.

**Step 2 — implement it in its OWN small class.** ⚠️ *Corrected 10 Aug 2026. An
earlier version of this plan said to put it on `VisitQueryService` because "that
class already holds both dependencies". **That reasoning was wrong and it creates a
dependency cycle Spring cannot start:***

```
WorkItemService → LaneVisitPort (VisitQueryService) → ProcessEngineGateway
                → engineListenerRegistrar → WorkItemIntake (WorkItemService)

BeanCurrentlyInCreationException: processEngineGateway is currently in creation
```

**The cause is a responsibility that was conflated.** `VisitQueryService` needs the
engine — but only for `currentActivity`, the live position on a *detail* read.
`activeVisitOn` needs no engine at all: two repositories and nothing else. Putting
the port on the engine-dependent class dragged the engine into `workitem`'s
dependency graph for no reason.

Create `execution/domain/LaneVisitLookup.java`:

```java
package com.lynxis.orca.runtime.execution.domain;

import java.util.Optional;

import com.lynxis.orca.runtime.execution.api.LaneVisitPort;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitReadRepository;

/**
 * Which visit is running on a lane — the whole of {@link LaneVisitPort}.
 *
 * <p><strong>Deliberately separate from {@code VisitQueryService}, and the reason is
 * structural rather than tidiness.</strong> That class needs the workflow engine to
 * report a visit's live position. This question does not: it is two repository reads.
 * Implementing the port there would put the engine in the dependency graph of every
 * consumer of this port — and {@code workitem} is one, while the engine's own
 * listener registration depends on {@code workitem}. That is a cycle, and Spring
 * refuses to start on it.
 *
 * <p>So the narrow port gets the narrow implementation, and the cycle cannot form.
 */
public class LaneVisitLookup implements LaneVisitPort {

	private final VisitReadRepository visits;
	private final AdmissionRepository lanes;

	public LaneVisitLookup(VisitReadRepository visits, AdmissionRepository lanes) {
		this.visits = visits;
		this.lanes = lanes;
	}

	@Override
	public Optional<Long> activeVisitOn(String laneExternalId) {
		// The port's own exception, not execution's domain one: the caller has to
		// catch this, and a caller that imports execution.domain to write the catch
		// clause has crossed the wall the port exists to keep.
		long laneId = lanes.laneIdOf(laneExternalId)
				.orElseThrow(() -> new LaneVisitPort.LaneNotPublishedException(laneExternalId));
		return visits.activeOnLane(laneId).map(VisitReadRepository.VisitRow::executionId);
	}
}
```

⚠️ **Package A is unaffected.** `VisitQueryService.onLane` keeps throwing
`AdmissionService.LaneNotAtThisInstallationException`, and `VisitController` keeps
catching it — both live in `execution`, so no wall is crossed. Only the cross-module
path needs the port's own type.

⚠️ **`VisitQueryService` must NOT implement `LaneVisitPort`.** If you already added
`implements LaneVisitPort` and an `activeVisitOn` method there, remove both. Its own
`onLane` method (work package A) stays exactly as it is.

⚠️ `VisitRow` does **not** currently expose `executionId` — the record is
`VisitRow(externalId, laneId, status, plate, startedAt, completedAt,
processInstanceId)`. **Add `long executionId` as its first component**, add
`execution_id` to `COLUMNS`, and read it in `map` with `rs.getLong("execution_id")`.
Every existing construction site must be updated; the compiler will list them.

**Step 3 — register the one bean.** In `ExecutionConfiguration`:

```java
	@Bean
	public com.lynxis.orca.runtime.execution.api.LaneVisitPort laneVisitPort(
			com.lynxis.orca.runtime.execution.persistence.VisitReadRepository visits,
			AdmissionRepository lanes) {
		return new com.lynxis.orca.runtime.execution.domain.LaneVisitLookup(visits, lanes);
	}
```

Both of its dependencies are plain repositories over the scope seam, so nothing here
reaches the engine and the cycle in step 2 cannot form.

⚠️ **Exactly one bean of this type must exist.** If `VisitQueryService` still
declares `implements LaneVisitPort`, its bean is a second candidate and Spring
refuses to choose:

```
NoUniqueBeanDefinitionException: expected single matching bean but found 2:
visitQueryService,laneVisitPort
```

⚠️ **Inject the INTERFACE, never the class.** In step 4, `WorkItemService` takes a
`LaneVisitPort` — the type from `execution.api`. Taking a concrete class from
`execution.domain` would compile and then fail `ModuleWallRule`, which forbids any
module from depending on another module's `domain` package. The port exists to make
that dependency legal; injecting past it defeats it, and the build check will say so.

**Step 4 — consume it in `workitem`.** Add to `WorkItemService`: add a
`private final LaneVisitPort laneVisits;` field, take it as a constructor parameter,
and pass it from the `workItemService` bean method at
`workitem/WorkItemConfiguration.java:73` — add `LaneVisitPort laneVisits` to that
method's parameters and Spring will inject the bean from step 3.

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

**Verified names, copy as written:** `WorkItem.QUEUED` is a constant inside the
`WorkItem` record in `workitem/domain/WorkItemTables.java`; `queuedAt()` and
`externalId()` are its components; `repository.openItemsOf(long)` exists and already
filters to `QUEUED` and `IN_PROGRESS`.

**Step 5 — the exception.** ⚠️ `WorkItemNotFoundException` is wrong here: its only
constructor takes an *external id* and wraps it in *"No work item 'X' exists under
this installation's scope."* Passing it a sentence yields gibberish. Add a sibling in
`WorkItemService`:

```java
	/**
	 * There is nothing on this lane for the caller to take.
	 *
	 * <p>Distinct from {@link WorkItemNotFoundException}, which is about an item the
	 * caller named. Here the caller named a LANE, so the message has to say which of
	 * the two reasons applies — an operator told "not found" about an item they never
	 * mentioned will go looking for the wrong fault.
	 */
	public static class NothingToTakeOnLaneException extends RuntimeException {

		public NothingToTakeOnLaneException(String laneExternalId, String because) {
			super("Nothing to take on lane '" + laneExternalId + "': " + because + ".");
		}
	}
```

**Step 6 — controller.** Add `takeNextOnLane` to `workitem/api/WorkItemController.java`,
following how `takeWorkItem` in that file resolves the operator and maps exceptions.
Map:

| Caught | Answer |
|---|---|
| `WorkItemService.NothingToTakeOnLaneException` | `WorkItemErrorCode.WORK_ITEM_NOT_FOUND` (404) |
| `LaneVisitPort.LaneNotPublishedException` | `ExecutionErrorCode.LANE_NOT_AT_THIS_INSTALLATION` (422) |

⚠️ **Catch `LaneVisitPort.LaneNotPublishedException`, never
`AdmissionService.LaneNotAtThisInstallationException`.** The second lives in
`execution.domain`, and catching it — or even calling `getMessage()` on it — makes
`workitem` depend on that package. `ModuleWallRule` fails the build on both.

⚠️ `ExecutionErrorCode` lives in `execution.api`, so referencing it from `workitem` is
permitted — `api` packages are what modules are allowed to see. `WorkItemController`
already imports `ExecutionErrorCode` for other routes; check before adding it twice.

### B.5 Tests

Add to `services/orca-runtime/src/integrationTest/java/com/lynxis/orca/runtime/workitem/WorkItemLifecycleIT.java`.
**That suite already has every helper you need — use them, do not build a second harness:**

| Helper | What it gives you |
|---|---|
| `admitAndPark()` | Admits a truck and parks it at the manual step; returns the visit's external id |
| `queuedItemOf(visitExternalId)` | The queued work item for that visit |
| `controllerFor(operator)` | A controller acting as a named operator |
| `claim(barrier, itemExternalId, operator)` | The two-thread race helper — see the existing test at line ~232, *"two operators claim the same item at the same instant"* |
| `itemStatus(itemExternalId)`, `statusOf(visitExternalId)` | Status assertions |
| `inScope(...)` | Runs an action in the installation's scope |

Write two tests:

1. **`takeNextOnLaneClaimsTheOldestQueuedItem`** — `admitAndPark()`, then
   `takeNextOnLane` returns that item as `IN_PROGRESS` with the caller as assignee,
   and the audit trail contains `TAKE`.
2. **`takeNextOnLaneHasExactlyOneWinner`** — park a visit with one queued item, race
   two operators through `takeNextOnLane` using the same `CyclicBarrier` shape as the
   existing claim race. Assert **exactly one** returned an item and the other saw the
   typed conflict — never two winners, never a silent no-op.

**Watch it fail:** in `takeNextOnLane`, replace `return take(next.externalId(), actor);`
with a direct unguarded repository update. Test 2 must fail. Restore it.

### B.6 Done when

Both tests pass, both watched to fail, and live: a parked truck's lane returns the
item on `take-next`; a clear lane returns `404`; `LANE-NOPE` returns `422`.

---

## 5 · Work package C — `POST /visits/{id}/abort`

### C.1 Behaviour

| Case | Answer |
|---|---|
| The visit is running | `200`, `LaneResetEnvelope` — visit `FAILED`, open items `FAILED`, lane free |
| No such visit under this scope | `404` `VISIT_NOT_FOUND` |
| The visit is not `ACTIVE` | `409` — and **nothing changes** |
| No resolvable operator | `401` `OPERATOR_UNRESOLVED` |

### C.2 The critical instruction

⚠️ **`LaneResetService.reset(laneExternalId, actor)` already does exactly this**,
addressed by lane. It takes the lane lock, finds the active root visit, fails its
open work items, terminates the process instance, marks the visit `FAILED` and frees
the lane — all in one transaction.

**Add a visit-addressed entry point to that existing service. Do not write a second
abort.** Two implementations of "tear a visit down" that drift apart is the defect
class this codebase spends build checks preventing.

⚠️ **There is a race you must close, and it is the whole reason this is not a
one-liner.** If abort resolves the visit's lane and then calls `reset(lane)`, the
visit could finish and a *new* truck be admitted in between — and `reset` would abort
the new one, because it aborts whatever is active on the lane. **The identity check
must happen under the lane lock, inside the transaction.**

**Prescribed shape** — add to `LaneResetService`:

```java
	/**
	 * Abort one named visit.
	 *
	 * <p>⚠️ The visit's identity is re-checked <em>under the lane lock</em>, not
	 * before it. Resolving the lane outside the transaction and then resetting it
	 * would abort whatever is active by then — and between the two, this visit can
	 * finish and the next truck be admitted. The caller named a visit; anything else
	 * being aborted in its place is the failure this guard exists to prevent.
	 *
	 * @throws VisitNotAbortableException when the visit is no longer the lane's
	 *         active visit — including when it has already finished
	 */
	public LaneReset abort(String visitExternalId, String actor) {
		AdmissionRepository.VisitRow visit = repository.visitByExternalId(visitExternalId)
				.orElseThrow(() -> new VisitNotFoundException(visitExternalId));

		return transactions.execute(status -> {
			if (!repository.lockLane(visit.laneId())) {
				throw new VisitNotAbortableException(visitExternalId, "its lane has no session row");
			}

			Optional<AdmissionRepository.ActiveVisit> active = repository.activeRootOn(visit.laneId());
			if (active.isEmpty() || !active.get().externalId().equals(visitExternalId)) {
				throw new VisitNotAbortableException(visitExternalId, "it is no longer running");
			}

			int failedItems = workItems.failOpenItemsFor(visit.executionId(), actor);
			if (visit.processInstanceId() != null) {
				engine.terminate(visit.processInstanceId(), "visit aborted by " + actor);
			}
			repository.completeVisit(visit.executionId(), Execution.FAILED);
			repository.bindLane(visit.laneId(), null, false);

			return new LaneReset(null, visit.externalId(), failedItems);
		});
	}
```

⚠️ `LaneReset`'s first component is `laneExternalId`. This path knows the lane's
internal id, not its external one. **Resolve it** with
`repository.laneExternalIdOf(visit.laneId()).orElse(null)` and pass that instead of
`null`, so the response is the same shape a lane reset returns.

**Verified names in the block above — copy as written:**

| Symbol | Where it comes from |
|---|---|
| `repository`, `engine`, `workItems`, `transactions` | Existing private final fields on `LaneResetService` (types `AdmissionRepository`, `ProcessEngineGateway`, `WorkItemIntake`, `TransactionTemplate`) |
| `repository.visitByExternalId(String)` | Returns `Optional<VisitRow>` where `VisitRow(executionId, externalId, laneId, status, plate, processInstanceId)` — note this is `AdmissionRepository`'s `VisitRow`, **not** `VisitReadRepository`'s |
| `repository.lockLane`, `activeRootOn`, `completeVisit`, `bindLane`, `laneExternalIdOf` | All exist on `AdmissionRepository` |
| `Execution.FAILED` | `ExecutionTables.Execution.FAILED` — the import in `LaneResetService` is already `com.lynxis.orca.runtime.execution.domain.ExecutionTables.Execution` |
| `workItems.failOpenItemsFor(long, String)` | On the `WorkItemIntake` port |

⚠️ **Two different records are called `VisitRow`.** `AdmissionRepository.VisitRow`
carries `executionId` and is what this abort uses. `VisitReadRepository.VisitRow` is
the read model's, and is the one §4.4 step 2 tells you to add `executionId` to. Do
not confuse them; the compiler will not always save you because both are in scope
via different imports.

Add both exceptions as static nested classes on `LaneResetService`, and map them in
the controller: `VisitNotFoundException` → `ExecutionErrorCode.VISIT_NOT_FOUND` (404,
already exists); `VisitNotAbortableException` → a **new** code
`VISIT_NOT_ABORTABLE` (409) added to `ExecutionErrorCode`.

### C.3 Contract

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

        Addressed by visit rather than by lane, and the visit's identity is
        re-checked under the lane lock — so a visit that finished while the request
        was in flight is refused rather than having the next truck aborted in its
        place.

        Aborting a visit that is no longer running answers `409`. Unlike resetting an
        already-clear lane, which is an ordinary outcome, naming a specific visit and
        being wrong about its state is a mistake worth reporting.
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

⚠️ Reuse `LaneResetEnvelope`. Do not define a new schema for the same data.

The controller method goes in `execution/api/VisitController.java`. It needs an actor
— follow `LaneResetController.resetLane`, which resolves `OperatorIdentity` and
throws `OPERATOR_UNRESOLVED` when there is none.

### C.4 Tests

Add to `WorkItemLifecycleIT` (⚠️ **there is no separate lane-reset suite** — lane
reset is tested there, and its helpers are what you need).

⚠️ **The guard has TWO conditions and they need a test each.** This was got wrong the
first time this plan was written, and the error is worth understanding because it is
the kind that produces a green suite guarding nothing:

```java
if (active.isEmpty() || !active.get().externalId().equals(visitExternalId)) {
```

- `active.isEmpty()` — nothing is running on the lane at all.
- `!…equals(visitExternalId)` — **something else** is running. This is the condition
  that stops abort killing the next truck, and it is the entire reason the check
  happens under the lock.

A test that aborts the same visit twice only ever reaches the **first** condition:
the successful abort unbinds the lane, so the second attempt finds it empty. **Delete
the identity comparison and that test still passes** — it certifies a guard it never
executes.

Write three tests:

1. **`abortFailsTheVisitAndItsItemsAndFreesTheLane`** — `admitAndPark()`, abort,
   assert visit `FAILED`, item `FAILED`, and a subsequent admission on the lane
   succeeds.
2. **`abortingAVisitThatIsNoLongerRunningChangesNothing`** — abort twice. The second
   raises the conflict and the item's status is unchanged. *(This covers the empty
   branch only.)*
3. **`abortingASUPERSEDEDVisitDoesNotTouchTheVisitThatReplacedIT`** — the one that
   matters:
   - `admitAndPark()` → visit **A**
   - abort **A** (succeeds, lane freed)
   - `admitAndPark()` again → visit **B**, now running on the same lane
   - abort **A** again → must raise `VisitNotAbortableException`
   - **assert B is untouched**: still `ACTIVE`, its work item still `QUEUED`, and the
     lane still bound to B

**Watch each fail, and note they fail for different reasons:**

| Break this | Which test must fail |
|---|---|
| Delete `active.isEmpty() \|\|` | Test 2 |
| Delete `\|\| !active.get().externalId().equals(visitExternalId)` | **Test 3** — and test 2 must still pass, which is the proof that test 2 alone was never enough |

Restore both afterwards.

---

## 6 · ⚠️ Ten traps — read before coding, not after the build breaks

1. **The scope seam refuses JOINs.** `ScopedSelect.from("a JOIN b")` throws at
   runtime; table names are allow-listed as `name` or `schema.name`. One table per
   statement. Need two? Two reads, joined in Java.
2. **Column names must be bare.** `.columns("e.external_id")` is refused. Use
   `"external_id"`.
3. **A `@RestController` taking a config value needs an explicit `@Bean` method.**
   Component scanning finds the class and cannot construct it — there is no `String`
   bean to autowire. **The symptom is every integration suite in the service failing
   at once with a context-load error**, which looks catastrophic and is a two-line
   fix. See `visitController` in `ExecutionConfiguration`.
4. **`ScopeContext.callIn` takes a `Callable`, not a `Supplier`.**
5. **Generated `StatusEnum.fromValue()` THROWS on an unknown value** — it does not
   return null. Using it as a validity test turns a typo into a `500`. Compare
   against `values()`.
6. **Bean validation enforces `minimum`/`maximum` but NOT `enum` membership.** A
   declared enum on a query parameter is documentation, not a check.
7. **Resolve lookups once per page, never per row.** A lookup inside `.map()` over a
   result list is an N+1 — measured at 67 queries for 66 rows in this codebase before
   it was fixed. `VisitQueryService.search` shows the batched shape.
8. **Read timestamps as UTC explicitly** —
   `rs.getTimestamp(col, Calendar.getInstance(TimeZone.getTimeZone("UTC")))`. Plain
   `getTimestamp` applies the JVM zone to a UTC value: invisible on a UTC machine,
   wrong everywhere else.
9. **`./gradlew check integrationTest` lies without `--rerun-tasks`.** It answers
   from cache in under a second and reports a success it never ran.
10. **`core.topology_lane` does not exist in a bare test schema** — it is a view core
    publishes. A repository test that migrates only `runtime` cannot resolve lane
    identifiers; test that at the service layer with a stub. `VisitReadPropertiesIT`'s
    `CountingLanes` shows how.
11. **A running service poisons the integration suite.** They share the `runtime`
    schema, and the running engine's async executor picks up the suite's jobs. The
    symptom is the 8-lane admission property failing with an unexplained `500` —
    which reads exactly like a real concurrency defect. **Stop the services before
    any suite run.** §0.0.
12. **A compound guard needs a test per condition, and "watch it fail" must break the
    condition you mean.** `if (a || b)` with a test that only ever triggers `a` will
    pass with `b` deleted — so the test certifies a guard it never executes, and the
    build stays green while the protection is gone. When you break something to watch
    a test fail, **break the exact clause**, and if the test still passes, the test is
    wrong rather than the code being safe. This plan shipped that mistake in §5.4 and
    it was found only by performing the exercise.

---

## 7 · Verification — run every row and paste the real output into the report

### 7.1 Build and tests

⚠️ **Stop the services before this section and restart them before §7.3** — §0.0. A
suite run against running services fails on the 8-lane admission property for reasons
that have nothing to do with your change.

| # | Command | Expected |
|---|---|---|
| 0 | `lsof -nP -iTCP -sTCP:LISTEN \| grep -E ":(1808[1-6]\|9100)\b"` | Prints nothing. If it does not, stop them first |
| 1 | `./gradlew build` | `BUILD SUCCESSFUL` from a clean tree |
| 2 | `./gradlew check integrationTest --rerun-tasks` | `BUILD SUCCESSFUL`. **`--rerun-tasks` is not optional** (trap 9) |

**Prove the tests actually ran** — a green build with a filtered-out suite is not a
pass:

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
tot=f=0; n=0
for p in glob.glob('**/build/test-results/integrationTest/*.xml', recursive=True):
    r=ET.parse(p).getroot(); n+=1
    tot+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0))
print(f"integrationTest: suites={n} tests={tot} failures={f}")
EOF
```

**Compare against the baseline you recorded in §0.9.** The count must have risen by
at least the number of tests you added — four if you wrote exactly the tests this
plan specifies, so **at least 233** from a baseline of 229 — and `failures` must be
`0`.

⚠️ **If the count did not rise, your tests did not run**, whatever the build said.
That is the single most common way a green build hides an empty one: a suite that
was filtered out, or a class the runner never discovered, still lets
`BUILD SUCCESSFUL` print.

### 7.2 The gate path still works

| # | Command | Expected |
|---|---|---|
| 3 | `./gradlew sendPlate -Pplate=T-CHECK-01 -Pport=9100`, then query `runtime.execution` | Newest row `COMPLETED` |

### 7.3 Live endpoint checks

Token and operator link per §0.7. Park a truck per §0.8.

| # | Call | Expected |
|---|---|---|
| 4 | `GET /api/v1/lanes/LANE-DEMO-01/visit` with a truck parked | `200`, the visit, `currentActivity: "manualInput"` |
| 5 | Same, after aborting or completing | `200`, `"data": null` |
| 6 | `GET /api/v1/lanes/LANE-NOPE/visit` | `422` |
| 7 | `GET /api/v1/lanes/LANE-DEMO-01/visit` with no token | `401` |
| 8 | `POST /api/v1/lanes/LANE-DEMO-01/take-next` with a parked truck | `200`, item `IN_PROGRESS`, assignee `usr-demo-clerk` |
| 9 | `GET /api/v1/work-items/{id}/audit` after 8 | Contains a `TAKE` row |
| 10 | Repeat 8 immediately | `404` — the item is no longer `QUEUED` |
| 11 | `POST /api/v1/lanes/LANE-NOPE/take-next` | `422` |
| 12 | `POST /api/v1/visits/{id}/abort` on a parked visit | `200`; then `runtime.execution` shows `FAILED` and `runtime.work_item` shows `FAILED` |
| 13 | Repeat 12 | `409`, and the work item's status is unchanged |
| 13b | Park a second truck on the freed lane, then abort the FIRST visit again | `409`, **and the second visit is still `ACTIVE` with its work item still `QUEUED`** — the identity guard, which row 13 alone does not reach |
| 14 | `POST /api/v1/visits/vis-nope/abort` | `404` |
| 15 | After 12, drive another truck at the lane | It admits — the lane was freed |

### 7.4 Everything watched to fail

| # | Check |
|---|---|
| 16 | Each of the three break-and-restore exercises in §3.4, §4.5 and §5.4 performed, the named test observed failing, and the code restored. **Record what you broke and the failure message** |

### 7.5 Services still boot

| # | Check |
|---|---|
| 17 | Restart core, runtime and edge; all three answer `200` on `/actuator/health`. **A green suite does not prove a service starts** — every suite builds its beans directly, and this repository has shipped a service that passed everything and could not boot |

### 7.6 Leave the environment as you found it

```bash
q "UPDATE runtime.connector_route SET http_status = 200 WHERE connector_name = 'tos'"
q "SELECT connector_name, http_status, outcome FROM runtime.connector_route"
```

Must read `tos | 200 | APPROVED`.

---

## 8 · When you are stuck

| Situation | Do this |
|---|---|
| A build check fails and you do not understand why | **Read the message in full** — they are written to be self-contained and name the violator. **Never disable or weaken a check** |
| You need another module's data | You need a port in that module's `api` package. §4.4 is the worked example. **Never** reach into another module's `persistence` or `domain` |
| Every test in the service suddenly fails to start its context | Trap 3. You added a controller without a `@Bean` method |
| Calls return `401 OPERATOR_UNRESOLVED` | §0.7 — the token's subject is not linked to `usr-demo-clerk`, or the token expired |
| This plan does not say what should happen in some case | **Ask. Do not decide.** Note it, and continue with the parts that are specified |
| Something in this plan appears wrong | Say so, with evidence, in the report. This plan has been wrong before; reporting it is worth more than working around it |
| A test passes and you have not watched it fail | It is not finished |

---

## 9 · Definition of done

Every one of these, with evidence:

1. Three endpoints implemented, contract-first, each satisfying a generated interface.
2. `./gradlew check integrationTest --rerun-tasks` green, with the test count risen
   to **≥ 233** and `failures=0`.
3. All seventeen verification rows in §7 executed, with real output recorded.
4. Every new test watched to fail, and what was broken recorded.
5. All three services boot after the change.
6. The connector route restored to `200 | APPROVED`.
7. `docs/lane-operations-report.md` written per §10.
8. Work committed on `feature/lane-operations`, one commit per work package, and
   **not** on `main`.

---

## 10 · The report — `docs/lane-operations-report.md`

1. **What was built**, per work package — and anything that was not.
2. **The §7 tables with real results**, including command output and the test count.
3. **Every decision this plan did not dictate** — at minimum: the oldest-first
   ordering in B, and how the `VisitRow` change in §4.4 step 2 rippled through
   existing call sites.
4. **What you broke to watch each test fail**, the failure message, and that it was
   restored.
5. **Anything that looked wrong** in this plan, the code, or the documents —
   **reported, not silently corrected.**

---

*Start with A. It is the smallest, it touches one module, and it teaches the shape
the other two reuse.*
