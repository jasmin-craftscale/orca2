# orca-media — placeholder

**This is not a Gradle module. It is a directory with a README, deliberately.**

`orca-media` is the seventh service in the architecture (§C7) and it is **not built
in this repository and not built in Phase 0**. It is delivered in its existing
technology — a Go WebRTC relay, an FFmpeg stream forwarder and Asterisk — because
real-time media is a specialism and rewriting a working relay buys nothing
(ADR-012).

## What that means concretely

| | |
|---|---|
| **Gradle module** | No. `settings.gradle.kts` does not include it, and adding it would make thirteen modules where the brief says twelve |
| **Application schema** | **None of its own.** The telephony engine reads its own `ps_*` configuration tables directly, in its own format. The platform does not read, write or model them, and they carry no ORCA retention rule |
| **Database schema and login** | A `media` schema and an `orca_media` login **do** exist — they are the seventh pair the bootstrap creates. They are the engine's, not the platform's: no ORCA migration writes there and no ORCA code reads it. The login exists so the engine connects as itself and is confined exactly like every other principal |
| **OpenAPI contract** | Not authored here. Its interface surface is inherited and documented in §C7 |
| **Health endpoint** | Its own, on its own surface (`/webrtc/api/health`, `/streamforwarder/api/health`) |

## How it is reached

Browsers never call it directly. `orca-runtime` authorises the operator and then
proxies every request, so the media path carries no authorisation decisions of its
own and is not exposed to an operator's browser as an independent surface.

## When it fails

Video and intercom stop. The gate is unaffected — no process step depends on a
stream, and a lane keeps running without an operator being able to see it.

## Why there is no OpenAPI contract here

Package 4b authors **six** contracts, one per built service, and this is the
seventh service without one.

§4b of the brief says "all seven contracts share the envelope from `_shared.yaml`",
and §3 and §6 of the same brief say `orca-media` is "a README, not a module" and
"a placeholder only — it is inherited and not built here". **Those two statements
cannot both be satisfied**, and the second one wins: this service's interface
surface is inherited, is documented in §C7 of the architecture, and is not ours to
design. Authoring a contract for it here would be publishing an API we did not
write, in a shape nothing in this repository implements — the exact thing §2 of the
brief forbids.

The discrepancy is reported rather than resolved. If the team wants a seventh
document, it should be transcribed from the inherited service by whoever owns it,
not composed here.
