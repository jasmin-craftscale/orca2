# orca-media — video and intercom

**Relays camera video to an operator's browser and carries operator-to-driver audio
at the lane. It is inherited, not rebuilt, and there is no code for it in this
repository.**

> ⚠️ **This directory is a README and nothing else — deliberately.** It is not a
> Gradle module, it has no Java, and it is not part of the build. If you are
> looking for the code, it is not here and was never meant to be.

| | |
|---|---|
| **Built here** | **No.** Delivered in its existing technology: a Go WebRTC relay, an FFmpeg stream forwarder, and Asterisk for telephony |
| **Gradle module** | No. It is absent from `settings.gradle.kts` on purpose |
| **Application schema** | **None of its own.** The telephony engine reads its own configuration tables directly, in its own format |
| **Database schema and login** | A `media` schema and an `orca_media` login *do* exist — the setup scripts create them as the seventh pair. They belong to the telephony engine, not to ORCA: no ORCA migration writes there and no ORCA code reads it. The login exists so the engine connects as itself and is confined exactly like every other principal |
| **OpenAPI contract** | Not authored here. Its interface is inherited, not designed by us |
| **Health** | Its own, on its own surface — `/webrtc/api/health` and `/streamforwarder/api/health` |

## Why it is not being rewritten

Real-time media is a specialism, the existing relay works, and it holds no business
logic worth modernising — there is no gate behaviour hiding inside it, just video
frames and audio. Rewriting a working relay on a different runtime would cost
months and buy nothing. It is treated the way the database and the identity server
are treated: a component the platform runs alongside, not one it owns.

The browser-side players *are* rebuilt as part of the new front end. It is only the
server half that is inherited.

## How it is reached

**Browsers never call it directly.** `orca-runtime` authorises the operator and
then proxies every request, so this component makes no authorisation decisions of
its own and is never exposed to an operator's browser as an independent surface.

## When it fails

Video and intercom stop. **The gate is unaffected** — no process step depends on a
video stream, and a lane keeps running perfectly well while an operator cannot see
it.

## Why there is no OpenAPI contract here

Six contracts are authored in this repository, one per service built here. This is
the seventh service and it has none, because its interface is inherited rather than
designed: writing a contract for it would mean publishing an API we did not write,
in a shape nothing in this repository implements.

The original build instructions were self-contradictory on this point — one section
said all seven services share the common response envelope, while two others said
this one is a README rather than a module and is not built here. Those cannot both
hold. The second reading won, and the discrepancy is recorded rather than quietly
resolved.

If the team ever wants a seventh contract document, it should be transcribed from
the running component by whoever owns it, not composed here from guesswork.
