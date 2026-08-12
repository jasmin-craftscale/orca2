# Open questions — 1.x source verification

**Verified against the `Lynxis-Gate` reference (the 25-service Go 1.x system), 10 Aug 2026.**
Scope: the four code-checkable items from `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` that need
ground truth rather than a product-owner decision — **NEW-5, the LPR image-bytes measurement
item, #28, and #17**. Every claim below carries file-and-line evidence. Where the register and
the code disagree, the finding says so rather than smoothing it over.

Paths are relative to `Lynxis-Gate/`.

---

## NEW-5 · PTZ preset path — CONFIRMED as a direct camera call, not a device-host command

The register's 7 Aug narrowing is correct, and the code confirms it fully.

`PTZ_PRESET` never touches the device host. It is served entirely inside
`camera-control-service` by a per-vendor driver registry, and the Axis driver issues a
**direct HTTP GET to the camera's VAPIX endpoint**:

- `services/camera-control-service/internal/drivers/axis_driver.go:28` — base URL is
  `{scheme}://{camera-ip}/axis-cgi/com/ptz.cgi`.
- `axis_driver.go:128-132` — `GotoPreset` sends `gotoserverpresetname={name}`; save/update/delete
  use `storepresetname` / `rmpresetname` (`:95-126`).
- `axis_driver.go:36-38` — auth is HTTP **Basic** with the camera's own username/password, not a
  Keycloak bearer token.
- `internal/drivers/registry.go:17-28` — vendor dispatch; default `cameraType` is `"AXIS PTZ Camera"`.
- `internal/handlers/camera_ptz_handler.go:39-77,250-289` — the handler receives an HTTP POST and
  calls the driver directly; there is no Kafka publish, no `event_dispatch` row, no device-host URL
  anywhere in the path.

**Consequence for 2.0:** `RestDeviceHost`'s refusal of `PTZ_PRESET` is correct **permanently**, not
provisionally. The real 2.0 question is only *where the direct camera-driver path lives* (edge owns
hardware contracts, so an edge module beside the LPR listener), and the vendor conversation for
NEW-4 does not gate it.

**Bonus (the print `encoding` sub-question in NEW-5):** the caller side *is* recoverable.
`data-capture-device-service/internal/repository/data_capture_device_repository.go:1216-1265`:
`encoding` defaults to `"binary"` (`:1216-1219`); `base64` is decoded (`:1235-1247`); anything else
is passed through as raw bytes (`:1248-1250`); the bytes are POSTed to
`{DeviceHostURL}/print/{format}` (`:1265`). What the .NET host *does* with `format`/`encoding`
remains unknown from this repo — that part of the register stands.

---

## LPR image bytes — CONFIRMED: the wire carries image *references*, not bytes, and ORCA drops them

This is the register's "honest unknown … very likely where the 20 GB/day sits." The code resolves
it, and points the other way.

1. **The camera wire never carries image bytes.** In the LPR packet DTOs
   (`data-capture-device-service/internal/dtos/xml_request.go`), every image element —
   `LPRImage` (`:55-61`), `PlateImage` (`:63-68`), `Image` (`:97-102`) — carries only
   `TIN`, `CameraId`, a `Path` string, `TimeStamp`, and a `PlateRect`. **There is no base64/binary
   image-content field anywhere in the packet.** Images live on the camera/NVR and are referenced
   by path.

2. **Even the path references are discarded on ingest.** The TCP listener parses the `ZapPacket`
   (`internal/handlers/data_capture_device_handler.go:544-545`), then flattens it via
   `ConvertJSONToData` into `dto.Data` (`:580,605`) — and `dto.Data` (`xml_request.go:7-18`) has
   **no image fields at all**: only `EventID/EventGUID/LaneID/LaneName/LPNumber/RecordID/
   ResultIndex/Confidence/CharacterConfidence`. Only `LPNumber` gates the forward
   (`:614`), and `PostMessageToTopic` forwards that flattened struct (`:615`). Grep for
   `LPRImage|PlateImage|Images` across the capture repository and service layers returns **zero**
   uses — nothing reads, forwards, or persists them.

**Consequence:** ORCA 1.x stores no LPR image bytes and not even the image path metadata; the images
are never in ORCA's custody. So the ~20 GB/day is **almost certainly not** LPR images in the ORCA
database — it points back at `event_dispatch`'s raw-payload text column (see #28) as the real
volume driver. The register's LPR-image hypothesis should be **retired**, and the one measurement
worth running is row-count-and-bytes on `event_dispatch`.

---

## #17 · Device-state replica — CONFIRMED: the replica already exists

The register's "third option — the replica already exists" is correct.

- `data-capture-device-service/internal/utils/enums.go:113,133` defines the `DEVICE_STATES` table
  constant, and `:148-155` lists it inside the `SYNC_DATA` module's replicated set, ordered by FK
  dependency (`DEVICE_STATES, // Depends on DEVICES`) — the comment at `:146` says the order
  "matches sync-service configuration tables."
- Every persisted device-state change registers a sync transaction:
  `internal/repository/data_capture_device_repository.go:161-198` — on coalescer flush-success it
  calls `registerDeviceStateSync`, building a `SyncTransactionRequest` with
  `EntityName = TableValues[DEVICE_STATES]`, `OperationType = SYNC_UPDATE` (`:194-198`). The
  registration deliberately fires **only after** the write lands (`:161-165`), so sync-service never
  publishes pre-write state.

**Consequence for 2.0:** the choice is not between two replica designs to build. `device_states` is
replicated today. The ruling that remains is only *where device state lives in the Cloud profile*,
where edge does not run — leaving that to implication is what produced the original contradiction.

---

## #28 · Purge, orphaned tickets, and the retention inversion — PARTIALLY VERIFIED (register is partly stale)

Two independent retention mechanisms exist in 1.x, and both look **newer than the register entry**
(their comments cite hardening plan tasks C3/G5r/G6). This changes the picture the register paints.

**1. `event_dispatch` DOES have a purge — contradicting the register.** The register says
event_dispatch is "excluded from purge by name … retained forever." In current code,
`workflow-connector-service/internal/retention/retention.go` runs a background goroutine that
**hard-deletes** `event_dispatch` rows in `COMPLETED`/`FAILED` older than the window
(`:71-106`), batched at 5000 (`:65,79`), on a ticker (`:39-57`). Default window is **enabled**:
`RETENTION_DAYS=30`, `RETENTION_INTERVAL_HOURS=24` (`internal/config/config.go:66-67,201-202`).
Caveat that keeps part of the register's spirit alive: **only terminal states are deleted**;
`PENDING`/`DISPATCHED` are never purged by design (`retention.go:24-25`), so stuck rows still grow
unbounded. Net: the "unbounded text column, retained forever" claim is **stale for the common case**
and should be re-verified in the register.

**2. A second cleanup covers sync transactions.** `sync-service/internal/services/cleanup_service.go`
soft-deletes `FAILED` sync transactions (`:20-44`, enabled, daily 2 AM cron `:89-125`) and
`COMPLETED` ones (`:54-82`) — the latter **gated off by default**
(`COMPLETED_TXN_RETENTION_ENABLED`, `:49,55`). This is soft-delete (`is_active/is_deleted`), unlike
the event_dispatch hard-delete.

**3. The execution-rooted "purge cascade" and the ticket-orphan bug — NOT LOCATED. Reported, not
guessed.** The register's core #28 claim — a staged cascade over `workflow_execution` →
`node_executions` → `work_items` → `portal_scan_data` whose "stage 3 does not check tickets," leaving
orphaned ticket rows — I could **not** find in the services I examined
(`workflow-connector`, `sync-service` cleanup, `admin-service` scheduler, and the `workitem-tracker`
/ `admin` service-file listings). It is not in the files above. It plausibly lives in one of the
large unread files (`sync-service`'s 939 KB `data_sync_repository.go`, `admin-service`'s 103 KB
`admin_service.go` / 70 KB `lane_monitoring_service.go`, or `workitem-tracker`'s
`workitem_tracker_service.go`). **This sub-claim is unverified** — one more targeted pass through
those files is needed before the ticket-orphan and `portal_scan_data` hard-delete findings can be
confirmed or retired.

---

## Summary

| Item | Verdict | One line |
|---|---|---|
| **NEW-5** | ✅ Confirmed | PTZ is a direct Axis VAPIX call from `camera-control-service`; never the device host. Print `encoding` defaults to `binary` on the caller. |
| **LPR image bytes** | ✅ Resolved | Wire carries image *paths*, not bytes; ORCA parses then drops them — no persistence. 20 GB/day is not LPR images; look at `event_dispatch`. |
| **#17** | ✅ Confirmed | `device_states` is already replicated (`SYNC_DATA` set + per-write sync registration). No replica to build. |
| **#28** | ⚠️ Partial | `event_dispatch` now HAS a 30-day terminal-state purge (register stale); sync cleanup exists; the execution-cascade **ticket-orphan** claim is **not yet located** — flagged, not guessed. |

*Prepared from the read-only `Lynxis-Gate` reference. Nothing there was modified.*
