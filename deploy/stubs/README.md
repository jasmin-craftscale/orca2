# `deploy/stubs/` — the two things a gate needs that ORCA does not build

Phase 1's demo runs one truck through one lane, end to end. Two of the parties in
that sequence are not ours:

| Stub | Stands in for | Contract |
|---|---|---|
| `tos` | The customer's **Terminal Operating System** | ORCA's own outbound connector call — shape defined by the site, not frozen |
| `device-host` | The per-lane **.NET device host** that drives the barrier | §D2's frozen device-host REST — **DERIVED-FROM-1X, see below** |

Both are [WireMock](https://wiremock.org/) with static mappings. They are
deliberately dumb: a stub that reasoned about the request would start encoding
assumptions about the real component, and the point of a stub is to be obviously
not it.

## The device-host mappings were a guess. They are now DERIVED-FROM-1X

§D2 freezes the device-host REST contract and says why — *a field-proven vendor
component that loads a driver plugin per device; changing this contract would mean
re-certifying every device vendor*. §C3 names what travels in each direction and
specifies the **inbound** half to the endpoint (`/device/{uuid}/data`,
`/device/{uuid}/io-state`, `/configurations/{area}/{lane}`).

**The outbound half is one sentence in the architecture**, and the mappings here
used to invent it: one `POST /api/v1/commands` carrying a JSON envelope. That guess
was wrong in every particular. `docs/device-host-outbound-from-1x.md` extracts the
real thing from the ORCA 1.x production caller — the Go service that commands real
barriers today — and these three mappings, and `RestDeviceHost`, now speak it:

| Mapping | Matches | Answers |
|---|---|---|
| `gate.json` | `POST /api/{device}/raiseGate` · `/lowerGate` | 200 + the five-field answer document ⇒ `EXECUTED` |
| `print.json` | `POST /api/{device}/print/{format}` | the same — note the request body is the file's **raw bytes with no `Content-Type`** |
| `refuse-io.json` | `POST /api/io/{device}/{port}/{true\|false}` | 503 — a host that is there and says no ⇒ `FAILED` |

**The `/api/io/` asymmetry is faithful, not a typo.** Gate and print address
`/api/{device}/…`; IO addresses `/api/io/{device}/…`.

**Success is `200` AND a body that decodes**, and both stubs return the shape 1.x
unmarshals: `{status, code, message, request_id, timestamp}`. A stub that answered
`200` with an empty body would be a stub that lets `RestDeviceHost`'s success rule
pass untested.

⚠️ **Still not settled, and not settleable here:** 1.x sends
`Authorization: Bearer <keycloak token>` on all three calls and nothing in either
repository says whether the .NET host validates it. These stubs do not require it
and `RestDeviceHost` does not send it — register **NEW-4**. A vendor document or a
test against a real host remains worth obtaining; this is evidence about the estate,
not a specification.

## Driving the stubs during a demo

Both expose WireMock's admin API, so a demo can change what they say without
restarting anything:

```bash
curl -s http://localhost:${ORCA_TOS_STUB_PORT:-9200}/__admin/requests | head -40
```

`docs/phase-1-demo.md` uses exactly that to show the barrier command arriving and
to show the expired-command case in which it does **not**.
