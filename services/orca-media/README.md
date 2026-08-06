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
| **Database login** | None. There is no seventh application login for media — the bootstrap creates a login for the six JVM services only |
| **OpenAPI contract** | Not authored here. Its interface surface is inherited and documented in §C7 |
| **Health endpoint** | Its own, on its own surface (`/webrtc/api/health`, `/streamforwarder/api/health`) |

## How it is reached

Browsers never call it directly. `orca-runtime` authorises the operator and then
proxies every request, so the media path carries no authorisation decisions of its
own and is not exposed to an operator's browser as an independent surface.

## When it fails

Video and intercom stop. The gate is unaffected — no process step depends on a
stream, and a lane keeps running without an operator being able to see it.
