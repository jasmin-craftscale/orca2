# Phase 4 — scope note (not yet a plan)

**Written by the outgoing orchestrator, 9 August 2026, as a starting point — not a specification.** The next session writes the real plan per the handover §10 shape (extract the 1.x reference first, then a self-contained plan with delegable work packages). This note exists so that scope, known traps and the relevant rulings do not have to be rediscovered.

## The remaining backend roadmap

`P4 · partner event API & integration` → `P5 · read models & notify` → `P6 · retention & purge jobs` → `P7 · core remainder (custom entities, licence verification)`. After those, the on-site backend is complete. **Out of scope throughout:** the frontend (unstaffed), and `orca-portal` / `orca-sync` / `orca-fleet` (cloud scope, deferred by the on-site-first ruling).

## What Phase 4 covers

**The inbound half of `orca-runtime`'s `integration` module** — the path a customer's own system uses to reach the gate, which today ends at the connector call built in Phase 1:

1. **The partner event API** — the endpoints §C2's interface surface marks 🔒: submit one event, submit a batch (which deliberately answers `207` with a per-item result array rather than the standard envelope), the callback an external system uses to answer a waiting workflow, the query endpoints, the atomic claim-the-next-event endpoint for partners that pull, and replay.
2. **`event_dispatch`** — the inbound dispatch queue: rows resolved against connector configuration (by slug + lane) and dispatched into the engine. This is the external-event → workflow entry path described in `Lynxis-Gate/CLAUDE.md`.
3. **Connector breadth** — beyond Phase 1's single REST connector: SOAP, the four authentication modes, and per-connector certificate trust (§B2, §D2). Note §B2's caveat: the TLS option is one-way (server-certificate verification); **mutual TLS does not exist in 1.x and is added work, not configuration.**

## Rulings that already shape it — do not re-derive

- **The partner event API is UNFROZEN** (register U2). It was frozen only because fielded customers had integrated against it; with new-clients-only, it is free to design. The register's advice: *take the freedom narrowly* — keep the route shape and the envelope, but fix the validation-error taxonomy (1.x returns roughly nine indistinguishable `400`s, three from a single join) and **take the `409` for the lost claim race, which closes open decision #6** (1.x returns `404`).
- **Everything else about the boundary stays frozen** — the customer's own TOS is not ours to change (§D2).

## Known traps, from work already done

Carry these into the extraction rather than discovering them:

- **`event_dispatch` has five shipped defects worth reading before designing the queue** (recorded during the 2026-07 red-team): claim-by-primary-key, lease + expiry + reaper, unique-constraint dedup, the ordering key, and discarded failures. The 2.0 equivalents are the outbox/idempotency/lease primitives that already exist — this queue should *consume* them, not reinvent them.
- **`event_dispatch` retention is inverted in 1.x**: it is excluded from purge by name, has no soft-delete columns, and holds every inbound payload in a `text` column — unbounded growth (register item 28). In 2.0 it is traffic-growing and needs its growth declaration, retention class and a scope-leading index the day it lands.
- **The connector client is rebuilt per call in 1.x** when per-connector TLS is configured — the strongest of the net-new performance findings. 2.0 should pool clients by credential/trust configuration.
- **The partner API's bulk endpoint deliberately breaks the envelope convention** (`207` + per-item results). That is in the architecture; `ErrorEnvelopeRule` will have an opinion — expect to reconcile them and record how.

## Where the 1.x reference must come from

An extraction sweep of `Lynxis-Gate`, read-only, in the established shape (`*-from-1x.md` with `file:line` evidence, the defects deliberately not carried forward, and the translation rules): the partner-event handlers and their response taxonomy, `event_dispatch`'s table and claim logic, `connector_config` / `connector_response_config`, and the connector invocation path including its authentication modes. `docs/core-config-schema-from-1x.md` §0's eleven translation rules still apply.

## Verification the plan should demand

The established table, plus these phase-specific ones: a redelivered partner batch has one effect (idempotency); two consumers racing the claim-the-next endpoint produce exactly one winner; a connector's circuit breaker opens without affecting other lanes or connectors; and **the Phase 1 demo and the Phase 3 clerk loop both still run end to end** — they are the standing regression canaries.
