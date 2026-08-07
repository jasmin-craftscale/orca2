# ORCA Phase 1 — the demo, from a clean machine to "the barrier confirmed"

**One truck, one lane, end to end.** A plate read arrives at `orca-edge` over the
camera's wire format → exactly one visit starts in `orca-runtime` → the process
calls a stubbed Terminal Operating System → commands the barrier through
`orca-edge` → the barrier confirms → the visit completes with its outbox fact in
one transaction.

Every command below is copy-paste. Every one of them was executed against this
repository while this file was written; §8 records what the run produced.

> **Two stubs stand in for things ORCA does not build** — a customer's Terminal
> Operating System and the per-lane .NET device host. The device-host stub speaks
> the **DERIVED-FROM-1X** routes: `POST /api/{device}/raiseGate`, the print call,
> and the `/api/io/…` asymmetry, extracted from the ORCA 1.x production caller
> (`docs/device-host-outbound-from-1x.md`). See `deploy/stubs/README.md`. **That is
> evidence about the fielded estate, not a vendor specification** — and one thing
> in it is still unruled: 1.x sends `Authorization: Bearer …` and ORCA does not,
> because nobody knows whether the host enforces it (register **NEW-4**).

---

## 1 · What you need

- A JDK (any recent one — the Java 25 toolchain is auto-provisioned)
- Docker, running
- Python 3 (only for `send-plate.py`, which speaks the camera's framing)

## 2 · The stack

```bash
cd deploy && cp .env.example .env
```

⚠️ **If `deploy/.env` already exists, do not overwrite it.** It is gitignored
precisely because it holds machine-local values — ports, in particular.

```bash
cd deploy && docker compose up -d
```

Four containers: SQL Server, Keycloak, and the two stubs. There is deliberately no
message broker (ADR-007).

```bash
cd deploy && ./bootstrap/run.sh
```

The privileged half — seven schemas, seven logins, grants. It runs once against a
fresh database and is a no-op afterwards.

## 3 · Ports, and the one thing that bites on a developer machine

The committed ports are the architecture's: `8081`–`8086` for the services, `1433`
for SQL Server, `8080` for Keycloak, `9100` for the camera listener.

**If ORCA 1.x is running on this machine it already holds those.** Everything below
therefore shows a **+10000 offset** for the services and reads the stack's ports
from `.env`. On a machine where the defaults are free, drop the `--server.port`
overrides and the `ORCA_DB_URL` export.

```bash
export ORCA_DB_URL='jdbc:sqlserver://localhost:21433;databaseName=orca;encrypt=true;trustServerCertificate=true'
export ORCA_OIDC_ISSUER_URI='http://localhost:18080/realms/orca'
```

⚠️ **`--spring.profiles.active=local` is not optional.** The committed
inter-service credential in `.env.example` is recognised by name, and
`InternalCredentialValidator` refuses to start the service with it unless `local`
is active (ADR-011). Set the profile; do not weaken the check.

## 4 · The three services

**Order matters, and the platform enforces it.** `orca-core` publishes the views
the gate path reads, so runtime and edge refuse to start before it has migrated —
naming the views they are waiting for, rather than failing later on a lane lookup.

```bash
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local --server.port=18081'
```

```bash
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local --server.port=18082 --orca.runtime.edge-base-url=http://localhost:18083'
```

```bash
./gradlew bootRun -p services/orca-edge --args='--spring.profiles.active=local --server.port=18083 --orca.edge.runtime-base-url=http://localhost:18082'
```

Each in its own terminal. Check all three:

```bash
for p in 18081 18082 18083; do curl -s -o /dev/null -w "$p %{http_code}\n" http://localhost:$p/actuator/health; done
```

## 5 · The demo site

```bash
cd deploy && ./demo/seed.sh
```

One site, one area, one lane, one camera, one barrier — plus the `tos` connector
pointing at the stub, and the one routing row that says `200` means `APPROVED`.

It runs in two halves under **two different logins**, and that is ADR-004 working
rather than an inconvenience: the topology is `orca_core`'s and the connector
configuration is `orca_runtime`'s, and neither login can write the other's schema.

It is a **deliberate act, never a profile**. Nothing runs it but a person, so no
profile, environment variable or ordering accident can put demo rows on a customer
site.

## 6 · One truck

```bash
./deploy/demo/send-plate.py --port 9100 --plate T-DEMO-01
```

Expect:

```
→ lane LANE-DEMO-01, plate T-DEMO-01, EventGuid evt-…
← <ZapPacket Type="ACK" Id="pkt-evt-…" Version="4.4" SenderId="999"></ZapPacket>
```

**That ACK is a durability receipt, not a courtesy.** It arrives only after the
capture is a committed row in `edge.event_buffer` — because a camera that has been
told "received" does not send that capture again, and acknowledging first turns a
restart into permanent data loss with nothing to show for it. (ORCA 1.x
acknowledges after the publish *attempt*; §5 of `docs/lpr-wire-format-from-1x.md`
lists that as one of three behaviours this deliberately does not copy.)

### What happens next, without you doing anything

Within a second or two: edge's delivery pump drains the lane to runtime's
`/internal/events/v1`; admission starts `gate-visit`; the async executor calls the
TOS stub, routes `200` to `APPROVED`, and commands the barrier through edge's
`/internal/commands/v1`; edge calls the device-host stub; the visit closes with
`visit.completed` recorded in the same transaction.

## 7 · Looking at what happened

A helper, so the queries below are one line each:

```bash
q() { docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -C -No -d orca -h -1 -W -Q "$1"; }
```

**The capture, buffered and acknowledged by runtime:**

```bash
q "SELECT TOP 3 event_uuid, lane_external_id, status, attempts, attributes FROM edge.event_buffer ORDER BY sequence_no DESC"
```

`status` is `ACKED` and `attributes` carries the normalised half — `plate`,
`confidence`, `resultIndex`. The raw `ZapPacket` stays in `payload`: edge is the
hardware boundary (§C3), so the vendor's dialect is decoded once, here, and never
crosses to runtime.

**The visit:**

```bash
q "SELECT TOP 3 external_id, status, plate, process_instance_id FROM runtime.execution ORDER BY execution_id DESC"
```

`COMPLETED`, with the plate the camera's *most confident* hypothesis carried — not
its first.

**The barrier command, and what the device host said:**

```bash
q "SELECT TOP 3 command_id, action, status, device_response FROM edge.command_log ORDER BY command_log_id DESC"
```

**The fact, in the visit's own transaction:**

```bash
q "SELECT TOP 3 publish_seq, ordering_key, event_type, payload FROM runtime.outbox ORDER BY publish_seq DESC"
```

⚠️ **Zero rows in `runtime.outbox_delivery`, and that is correct.** No consumer is
registered, because nothing on-site consumes `visit.completed` in Phase 1 — the
cloud tier that would is scoped later (register NEW-1b). The fact is *recorded*,
which is the guarantee that matters; delivery arrives with a destination.

**The buffer, as an operator sees it** (§C3's diagnostics, added in H3):

```bash
curl -s http://localhost:18083/internal/buffer/stats \
  -H 'X-Orca-Internal-Auth: local-dev-internal-credential-not-for-deployment' \
  -H 'X-Orca-Service: orca-runtime' | python3 -m json.tool
```

Every lane the site publishes, whether or not it has traffic: `depth`,
`oldestUndeliveredAgeSeconds`, `dead`, and `ownedByThisInstance`. **The age is
there because depth alone cannot tell a severed link from a busy morning** — a
depth of 4 that is nine hours old is an outage; a depth of 400 that is four
seconds old is traffic. `totalDead` is the number this endpoint exists for: an
event nobody could deliver used to be visible only to somebody with a database
login.

**The stubs' own view of it:**

```bash
curl -s http://localhost:9200/__admin/requests | python3 -m json.tool | head -40
```

```bash
curl -s http://localhost:9300/__admin/requests | python3 -m json.tool | head -40
```

## 8 · The four things worth demonstrating deliberately

### a · The camera's dedup key

```bash
./deploy/demo/send-plate.py --port 9100 --plate T-DEMO-02 --event-guid evt-fixed-demo --repeat 2
```

Two ACKs, **one** buffer row. A camera that never saw its acknowledgement sends the
same `EventGuid` again, and it must not become two visits' worth of events:

```bash
q "SELECT COUNT(*) FROM edge.event_buffer WHERE event_uuid = 'evt-fixed-demo'"
```

### b · Two trucks, one lane, in the same instant

```bash
./deploy/demo/send-plate.py --port 9100 --plate T-RACE & ./deploy/demo/send-plate.py --port 9100 --plate T-RACE & wait
```

Two events, **one** visit. One of them started it and the other joined it — both
correct outcomes, and the difference is visible in runtime's answer to the pump.
This is the property the whole design turns on, and it is proven 1,000 times in
`AdmissionThroughHttpIT` rather than once here.

### c · A customer system nobody wrote a branch for

Point the connector at a route with no mapping — WireMock returns 409 for a lane
named `LANE-DEMO-BLOCKED`, and no `connector_route` row maps 409:

```bash
q "UPDATE runtime.connector_route SET http_status = 418 WHERE connector_name = 'tos'"
```

```bash
./deploy/demo/send-plate.py --port 9100 --plate T-UNROUTED
```

The visit reaches `MANUAL`, and **the barrier is not commanded**. An answer nobody
wrote a branch for goes to a human, never to an implicit approval. Put it back:

```bash
q "UPDATE runtime.connector_route SET http_status = 200 WHERE connector_name = 'tos'"
```

### d · A stale command is discarded, and the device host sees nothing

The one that matters physically. A command whose deadline has already elapsed
describes a world that has moved on — the truck may have gone and another may be in
the lane.

```bash
curl -s -X POST http://localhost:18083/internal/commands/v1 \
  -H 'X-Orca-Internal-Auth: local-dev-internal-credential-not-for-deployment' \
  -H 'X-Orca-Service: orca-runtime' -H 'Content-Type: application/json' \
  -d '{"commandId":"cmd-stale-demo","laneExternalId":"LANE-DEMO-01","deviceExternalId":"DEV-DEMO-BARRIER","action":"RAISE_GATE","deadlineMs":0}'
```

The answer is `FAILED` with `"discarded as expired"` in `detail` — **not**
`UNKNOWN`, because `UNKNOWN` means nobody knows whether the device acted and here
everybody knows: nothing was sent. Confirm the device host was never called:

```bash
curl -s http://localhost:9300/__admin/requests | grep -c cmd-stale-demo
```

Zero. Replay the same `commandId` and the recorded outcome comes back rather than
a fresh attempt with a fresh clock.

## 9 · If something does not work

**`orca-runtime` fails to migrate: "There is already an object named 'ACT_GE_PROPERTY'".**
This database ran with `flowable.database-schema-update: true` at some point, so it
has the engine's tables and no Flyway history for them. **There is a procedure for
this now** — `docs/flowable-adoption.md`, added in H4. Stop runtime, then:

```bash
cd deploy && ./adopt-flowable/run.sh
```

It refuses without changing anything if those tables hold process data, or if they
were built by a Flowable version other than the one the migrations were extracted
from. Running it when you are not sure is safe: on a database that needs no
adoption it says so and does nothing.

**The visit reaches `MANUAL` instead of `COMPLETED`.** That is the platform working
— every failure has a branch and every branch ends somewhere a human can see. Look
at `runtime`'s log for the reason; the usual ones are the TOS stub not running,
`connector_config.base_url` pointing at a port nothing listens on, or a
`connector_route` row that no longer maps `200`.

**The camera gets silence rather than an ACK.** Edge does not own that lane, or the
site has no such lane. `grep "took ownership of lane"` in edge's log, and check
that `./demo/seed.sh` ran *before* edge's election cycle (it polls every 5 s, so it
picks up a newly seeded lane on its own).

## 10 · Tearing down

```bash
cd deploy && docker compose down
```

Add `-v` to drop the database volume as well, if you want the next run to start
from a genuinely empty database.

---

## 11 · What the run that produced this file actually printed

Executed 7 August 2026 against the local stack, on the +10000 offset.

```
→ lane LANE-DEMO-01, plate T-DEMO-01, EventGuid evt-dfc3d8c3-…
← <ZapPacket Type="ACK" Id="pkt-evt-dfc3d8c3-…" Version="4.4" SenderId="999"></ZapPacket>
```

```
BUFFER   ACKED  {"plate":"T-DEMO-01","confidence":"0.94","charConfidence":"0.93",
                 "resultIndex":2,"capturedAt":"2026-08-07T09:00:00","packetId":"pkt-evt-dfc3d8c3-…"}
VISIT    vis-f8c9f7eb-…  COMPLETED  plate=T-DEMO-01
COMMAND  RAISE_GATE  EXECUTED  {"status":"OK","note":"STUB, AND THE SHAPE IS PROVISIONAL…"}
OUTBOX   lane:LANE-DEMO-01  visit.completed
         {"visitExternalId":"vis-f8c9f7eb-…","laneExternalId":"LANE-DEMO-01","plate":"T-DEMO-01"}
DELIVERY rows: 0
```

`resultIndex: 2` is worth noticing: the camera sent two plate hypotheses and the
listener took the **more confident** one, which was the second.

The dedup demonstration:

```
← <ZapPacket Type="ACK" Id="pkt-evt-fixed-demo" …>   (twice)
buffer rows for evt-fixed-demo: 1
```

Two acknowledgements, one row. **Both ACKs are honest** — the row exists when each
is sent, which is exactly what the receipt claims.

The expired command:

```
{"status":"SUCCESS","code":"OK","data":{"commandId":"cmd-stale-demo",
 "detail":"discarded as expired: 3 ms elapsed of a 0 ms deadline. Nothing was sent.",
 "status":"FAILED"}, …}
device-host calls mentioning cmd-stale-demo: 0
```

Replayed with the same `commandId`, it answers with the recorded outcome — the same
`detail`, and the original `ackedAt` rather than a fresh one.

---

## 12 · What this demo does *not* show

Named here rather than left for a reviewer to discover:

- **A real camera.** The wire format is DERIVED-FROM-1X, not vendor-confirmed.
- **A real device host.** The outbound shape is now DERIVED-FROM-1X too, which is
  evidence about the fielded estate and still not a vendor document. Two things it
  cannot show: whether the host validates the `Authorization` header 1.x sends and
  ORCA does not (register **NEW-4**), and what a `PTZ_PRESET` addresses — the
  extraction has no route for it, so edge refuses the command (**NEW-5**).
- **A consumer of `visit.completed`.** There is none, deliberately.
- **Two edge instances handing a lane over.** That is proven in
  `EdgeIngestPropertiesIT` and needs a second appliance to demonstrate live.

*Companion: `docs/phase-1-plan.md` · `docs/phase-1-report.md` ·
`docs/lpr-wire-format-from-1x.md` · `deploy/stubs/README.md`.*
