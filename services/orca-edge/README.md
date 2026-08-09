# orca-edge — the hardware boundary

**Everywhere the platform meets equipment it does not control: cameras, barriers,
printers and IO ports. It speaks each vendor's protocol exactly as that vendor
defined it, and it is the only part of ORCA that does.**

If a lane is running, this service is running. It is the one component a lane's
availability actually depends on.

| | |
|---|---|
| **HTTP port** | `8083` |
| **Camera port** | `9100` — a raw TCP listener, not HTTP. Cameras connect to it directly |
| **Database schema** | `edge` |
| **Database login** | `orca_edge` — this service can reach no other schema |
| **Depends on** | `orca-core` for lane and device configuration, read through published views. It **refuses to start** without them |
| **Talks to** | `orca-runtime` (captures up, commands down) and the per-lane device host |
| **Status** | Built and working end to end against stubs. Never yet run against real equipment |

## What it is responsible for

- **Listening to cameras.** A plate read arrives over the camera vendor's own TCP
  protocol. This service decodes it, stores it durably, and only then acknowledges
  it.
- **Buffering.** Every capture is written to a durable per-lane queue before
  anything else happens. If runtime is restarting or the link is down, nothing is
  lost and order is preserved.
- **Commanding devices.** Raising and lowering a barrier, printing a ticket,
  setting an IO port — each through the device host's own REST contract.
- **Device state.** What each device is and what it was last known to be doing.

## What it deliberately does not do

- **It makes no decisions.** It never decides whether a truck may pass. It reports
  what happened and does what it is told.
- **It does not buffer outbound commands.** This is a safety decision, not an
  oversight — see below.
- **It does not let the vendor's dialect escape.** A camera's XML is decoded here,
  once, and never crosses to another service.

## Three behaviours worth understanding before changing anything

**The acknowledgement to a camera is a durability receipt, not a courtesy.** It is
sent only after the capture is a committed row. A camera that has been told
"received" will not send that capture again, so acknowledging any earlier would
turn a restart into permanent, silent data loss.

**Commands are never queued, and a stale one is discarded rather than delivered.**
Every command carries an expiry. A barrier command issued for a truck that left ten
minutes ago must not reach a barrier now — another truck may be standing there.
This is why an expired command is refused outright and the device host is never
called.

**A command is confirmed, not assumed.** Success means the device host both
acknowledged *and* returned a body that decodes. Anything else is an unknown
outcome, and an unknown outcome is resolved by *looking at the device*, never by
retrying blindly or assuming it worked.

## One instance owns a lane

Cameras and device hosts each talk to a single fixed address, so while everything
else in the platform runs on every server, **device handling for a given lane is
owned by one instance at a time**. Ownership is a lease held in the database, per
lane rather than per site, so one stuck owner cannot idle a whole facility.

Holding the lease is not permission to write: every buffer write carries a fencing
token, and an instance that stalled and lost its lease has its late write **refused
by the database**. You cannot guarantee a stalled process is dead; you can
guarantee its writes are rejected.

## What is built today

| Migration | Adds |
|---|---|
| `V100` | The empty baseline for this schema |
| `V101` | `event_buffer` and `device_state` — the durable capture queue and last-known device state |
| `V102` | The decoded, normalised half of a capture, stored alongside the raw packet |
| `V103` | `command_log` — every command issued, its expiry, its outcome and the device's own answer |

The buffer keeps **both** the raw packet and a decoded version. A durable buffer
that paraphrased its input would be worth less than one that did not, and decoding
once at arrival means a later parser change cannot alter what a stored row means.

⚠️ **The two wire protocols were reconstructed from the Go system in production
today, not from vendor documentation.** They are strong evidence about what the
fielded equipment expects, and they are not a specification. Two things remain
genuinely unknown: whether the device host requires an authorisation header (this
service sends none), and how a camera behaves when it is not acknowledged.

## Its interfaces

**Inbound from cameras** — a TCP listener on port 9100, speaking the vendor's
framing. Not HTTP, and not in the OpenAPI contract.

**HTTP** — four paths:

| Path | For |
|---|---|
| `/internal/commands/v1` | Runtime issues a device command; the answer says executed, failed, unknown or still in progress |
| `/internal/commands/{commandId}` | The recorded outcome of a command already issued — a replay returns what happened, never a bare "duplicate" |
| `/internal/buffer/stats` | Per-lane queue depth, the age of the oldest undelivered capture, dead events, and whether this instance owns the lane. Depth alone cannot tell a severed link from a busy morning; the age can |
| `/api/v1/health` | Liveness |

## Running it

```bash
./gradlew bootRun -p services/orca-edge --args='--spring.profiles.active=local'
```

Start `orca-core` first. To send a plate read in the camera's real wire format:

```bash
./gradlew sendPlate -Pplate=T-DEMO-01
```

## How it is tested

Four suites under `src/integrationTest`, against a real database.

| Suite | Proves |
|---|---|
| `EdgeIngestPropertiesIT` | Nothing is lost across a severed link and order is preserved; two instances contend for a lane and exactly one wins; a stalled instance's late write is refused and rolled back |
| `DeviceHostWireIT` | The exact bytes on the wire — method, path, query string, headers, body — asserted against a real socket rather than against this code's own abstractions |
| `DeviceCommandPropertiesIT` | Every command type replays to its recorded outcome; an expired command sends nothing at all |
| `BufferStatsPropertiesIT` | An empty lane reports no age rather than an age of zero — different facts, and zero reads as "something just arrived" |

`DeviceHostWireIT` is written the way it is deliberately: the suite it replaced
agreed with the code about a protocol neither had any evidence for, and passed.

## When it fails

| Failure | What happens |
|---|---|
| This service is down | Nothing reaches the gate loop and the lane stops. This is the service a lane's availability depends on |
| The link to runtime drops | Captures buffer locally, in order, and drain when it returns |
| A device host does not answer | The command's outcome is unknown, and the device's actual state is verified rather than assumed |

## Where to look first

1. `domain/LprListener.java` and `domain/LprFraming.java` — the camera protocol,
   and the acknowledge-after-commit rule.
2. `domain/RestDeviceHost.java` — the outbound device contract, including the
   deliberate asymmetries reproduced from the fielded system rather than tidied up.
3. `domain/LaneOwnership.java` — the per-lane lease, and why a fencing token
   matters more than the lease itself.
