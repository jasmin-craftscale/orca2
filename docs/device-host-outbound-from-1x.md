# Device-host outbound commands — extracted from the ORCA 1.x production caller

**⚠️ Provenance: derived from the fielded 1.x Go code, not from the vendor's specification.**
Source: `Lynxis-Gate/services/data-capture-device-service/internal/repository/data_capture_device_repository.go` — `PostGateStatus` (lines ~1333–1450), `PostPrintAction` (~1452–1568), `PostIOStatus` (~1785+), dispatch in `InvokeExternalAPIByTopic` (~1076–1330); endpoint maps in `internal/utils/utils.go` (~185–193); response DTO in `internal/dtos/data_capture_device.go` (163–169). This is what commands real barriers today. It supersedes the invented stub dialect; it does not supersede the vendor's document, which remains worth obtaining. Mark every derived implementation DERIVED-FROM-1X.

## 1 · The three calls

| Command | Request | Body |
|---|---|---|
| **Gate** | `POST {device_host_url}/api/{device_uuid}/raiseGate` or `.../lowerGate` | **empty** |
| **Print** | `POST {device_host_url}/api/{device_uuid}/print/{format}` | **raw file bytes** (upstream base64 is decoded before sending; an `encoding` field defaults to `binary`) |
| **IO** | `POST {device_host_url}/api/io/{device_uuid}/{io_port}/{true\|false}?ioPortName={url-escaped name}` | **empty** |

Note the asymmetry, faithfully: gate and print address `/api/{uuid}/...`; IO addresses `/api/io/{uuid}/...`. The gate call sets `Content-Type: application/json` despite the empty body; the print call **deliberately sets no Content-Type** (the 1.x code has it commented out). All three send `Authorization: Bearer <token>` — see §3.

## 2 · The response contract

Success in 1.x is **HTTP 200 AND a decodable JSON body** of this exact shape:

```json
{ "status": "...", "code": 200, "message": "...", "request_id": "...", "timestamp": "..." }
```

Non-200 → failure. 200 with an undecodable body → failure. This maps cleanly onto the 2.0 command lifecycle: 200-and-decodable ⇒ `EXECUTED`; non-200 or undecodable ⇒ `FAILED`; no answer by deadline ⇒ `UNKNOWN` — which is §B10's *"an acknowledgement **and** a body that decodes"* rule already fielded in 1.x, worth saying out loud.

## 3 · ⚠️ The authentication finding — needs a ruling before this contract is implemented for real

**1.x mints a Keycloak token for every device-host command** — client-credentials via the service's Keycloak client, realm from request context or the configured gate realm — and presents it as `Bearer`. Two consequences:

1. **Whether the .NET host *validates* that token cannot be determined from this repository.** The host is the vendor's component. If it enforces the token, then ORCA 2.0's edge must present a token the host accepts — which collides with ADR-011 ("no service mints a token") **on the barrier path itself**, precisely where §A1 forbids an IdP dependency. If it ignores the header, the Bearer is cargo and can be dropped. **Only the vendor, the host's configuration, or a test against a real host can answer this.**
2. **If the host does enforce it, 1.x today cannot command a barrier while Keycloak is unreachable.** That is an operationally significant property of the current product and worth confirming for its own sake.

Until ruled: implement the wire shape without the auth header, keep the header's slot visible and marked OPEN-QUESTION, and do not invent a resolution — the options (local issuance for this one external contract, a long-lived offline token, vendor accepting a static credential) each have different costs and the choice is the product owner's with the vendor.

## 4 · What the frozen contract deliberately lacks — and what that means for 2.0

**There is no command identifier, no idempotency key, and no expiry anywhere in these calls.** A network-level retry against the host is a second physical actuation. This validates the 2.0 design decisions rather than complicating them: commands are never queued, never blindly retried, expiry is enforced **before** the call, and dedup by `command_id` lives on edge's side of the boundary because the boundary itself cannot carry one.

## 5 · What this document cannot tell you

- The host's timeout behaviour and whether it has any internal retry.
- Status codes the host actually emits beyond 200 (1.x collapses all non-200 into one failure).
- Whether the token is validated (§3) — the single most consequential unknown.

*Extracted 7 Aug 2026 by the orchestrator session. Companion: `docs/lpr-wire-format-from-1x.md` (the inbound half of the hardware boundary), `deploy/stubs/README.md`.*
