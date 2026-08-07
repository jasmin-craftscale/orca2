# `deploy/stubs/` — the two things a gate needs that ORCA does not build

Phase 1's demo runs one truck through one lane, end to end. Two of the parties in
that sequence are not ours:

| Stub | Stands in for | Contract |
|---|---|---|
| `tos` | The customer's **Terminal Operating System** | ORCA's own outbound connector call — shape defined by the site, not frozen |
| `device-host` | The per-lane **.NET device host** that drives the barrier | §D2's frozen device-host REST — ⚠️ **outbound shape PROVISIONAL, see below** |

Both are [WireMock](https://wiremock.org/) with static mappings. They are
deliberately dumb: a stub that reasoned about the request would start encoding
assumptions about the real component, and the point of a stub is to be obviously
not it.

## ⚠️ The device-host mappings are a guess, and that matters

§D2 freezes the device-host REST contract and says why — *a field-proven vendor
component that loads a driver plugin per device; changing this contract would mean
re-certifying every device vendor*. §C3 names what travels in each direction and
specifies the **inbound** half to the endpoint (`/device/{uuid}/data`,
`/device/{uuid}/io-state`, `/configurations/{area}/{lane}`).

**The outbound half — the request body, the route and the response document for a
command — is one sentence, and nothing in this repository records it.** So
`device-host/mappings/raise-gate.json` speaks the same provisional dialect as
`RestDeviceHost`, and a green demo proves the plumbing and nothing about the
vendor.

Before this reaches a real device host, someone must obtain the vendor's
specification or extract the outbound calls from the 1.x estate — the way
`docs/lpr-wire-format-from-1x.md` was extracted for the camera.

## Driving the stubs during a demo

Both expose WireMock's admin API, so a demo can change what they say without
restarting anything:

```bash
curl -s http://localhost:${ORCA_TOS_STUB_PORT:-9200}/__admin/requests | head -40
```

`docs/phase-1-demo.md` uses exactly that to show the barrier command arriving and
to show the expired-command case in which it does **not**.
