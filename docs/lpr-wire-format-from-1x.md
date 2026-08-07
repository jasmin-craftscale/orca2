# LPR wire format — extracted from the ORCA 1.x production listener

**⚠️ Provenance: derived from the fielded 1.x Go code, not from a vendor specification.**
Source: `Lynxis-Gate/services/data-capture-device-service` — `internal/handlers/data_capture_device_handler.go` (TCP loop, lines ~424–659), `internal/utils/utils.go` (ACK/NAK + plate selection, lines ~227–288), `internal/utils/constants.go` (framing bytes, lines 76–77), `internal/dtos/xml_request.go` (the full XML schema). This is what the code that talks to real cameras today actually does. It supersedes guesswork; it does not supersede a vendor document or a capture from a fielded camera — both remain worth obtaining, and every claim here should be marked DERIVED-FROM-1X in orca source.

## 1 · Framing — delimiter-based, NOT length-prefixed

```
STX = 0x02 · ETX = 0x03
packet on the wire:   [0x02] <ZapPacket ...>...</ZapPacket> [0x03]
```

The 1.x reader accumulates the TCP stream into a buffer, scans for `STX`, then for the first `ETX` after it; the payload is the bytes **strictly between** the two delimiters; everything through the `ETX` is consumed and the scan repeats, so **multiple packets per read and packets split across reads are both normal**. There is **no length prefix anywhere**.

> **This corrects Phase 1's provisional implementation** — `phase-1-report.md` §5.6 assumed "length-prefixed bytes". The fielded framing is STX/ETX-delimited. `LprFraming`'s provisional implementation must change.

No escaping of 0x02/0x03 inside the payload is handled by 1.x — the payload is XML text, where raw control bytes do not legitimately occur.

## 2 · The inbound packet — `ZapPacket`, Version 4.4

Root attributes: `Type` (`"MSG"` for captures), `Id`, `Version` (`"4.4"`), `SenderId`, `SenderName`, `SenderSysType`, `SenderVersion`.

Element tree (from the 1.x DTOs, names verbatim):

```
ZapPacket
└── Event
    ├── EventId · EventGuid · Online (bool) · TimeStamp · LaneId · LaneName
    ├── Trigger { RequestTimeStamp, TriggerId, DataSet { Name@, Data { Name@, Value@ } } }
    ├── LP []            ← one per plate hypothesis
    │   ├── AutoLPR                      ← the plate text
    │   ├── Confidence · CharConfidence
    │   ├── State · StateConfidence · PlateType
    │   ├── LPRImage  { TIN@, CameraId@, Path, TimeStamp, PlateRect{left@,right@,top@,bottom@} }
    │   └── PlateImage { TIN@, CameraId@, Path, TimeStamp }
    └── Images
        ├── LPRImages  { Image[] { TIN@, CameraId@, Path, TimeStamp } }
        └── ViewImages { Image[] { ... } }
```

(`@` marks XML attributes; everything else is an element.)

**Images travel as filesystem `Path` references, never as bytes** — consistent with §D2's "images referenced by filesystem path", and directly relevant to the register's LPR-image-bytes measurement question: image bytes never cross this wire.

## 3 · Acknowledgement format

Exact bytes 1.x sends (utils.go:280–288):

```
[0x02]<ZapPacket Type="ACK" Id="{inbound Id}" Version="4.4" SenderId="999"></ZapPacket>[0x03]
[0x02]<ZapPacket Type="NAK" Id="{inbound Id}" Version="4.4" SenderId="999"></ZapPacket>[0x03]
```

- **ACK** is sent only for packets whose `Type == "MSG"`, after processing.
- **NAK** is sent when the XML fails to parse. Note the 1.x quirk: on a parse failure the `Id` is unavoidably empty (`Id=""`), because it comes from the packet that failed to parse.

## 4 · Plate selection semantics (1.x behaviour)

Among `Event.LP[]`, the entry with the **highest numeric `Confidence` wins**; the derived capture carries the winner's `AutoLPR`, its `Confidence` and `CharConfidence`, and `result_index` = the winner's **1-based** position in the list. Unparseable confidence values are skipped.

## 5 · 1.x behaviours orca must NOT copy — the ack is not a durability receipt today

Observed in the 1.x handler, listed so nobody mistakes them for contract:

1. **1.x ACKs regardless of processing outcome.** The ACK is written after the Kafka publish *attempt*, even when that publish failed (the failure is only logged), and events with an empty plate are silently skipped yet still ACKed. The orca design — **persist to the durable buffer *before* sending ACK** — is strictly stronger and is the correct reading of "the ack is a durability receipt, not a courtesy". The camera-side contract (ACK expected per MSG) is unchanged by this improvement.
2. **A mid-stream JSON failure kills the whole connection handler** (`return` inside the loop) — a per-packet fault becomes a connection fault. Orca should fail the packet, not the connection.
3. **Nothing distinguishes duplicate deliveries** — 1.x has no dedup at this layer. Orca's `event_uuid` dedup key (from `EventGuid`) is new and correct.

## 6 · What this document cannot tell you

- Whether the camera retries on NAK or on ACK timeout, and with what backoff — **only a vendor spec or a fielded capture answers this.**
- Whether fields beyond the DTOs exist on the wire (the 1.x structs decode what 1.x uses; unknown XML elements are silently ignored by Go's decoder).
- Byte-level encoding corner cases (charset declarations, BOMs) — 1.x assumes UTF-8 text and none were handled specially.

*Extracted 7 Aug 2026 by the orchestrator session from `feature/OCS-4` @ `2e7a0f332` lineage. Destination once the build session is idle: `orca/docs/` beside the BPMN execution profile, and the DERIVED-FROM-1X markers in `LprFraming`.*
