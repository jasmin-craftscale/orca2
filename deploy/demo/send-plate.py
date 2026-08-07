#!/usr/bin/env python3
"""Speak the camera's wire format at orca-edge's LPR listener.

DERIVED-FROM-1X. Every byte below comes from docs/lpr-wire-format-from-1x.md,
which was extracted from the ORCA 1.x production listener — the Go service that
talks to real cameras today. It is not a vendor specification, and §6 of that
document lists what neither it nor this script can answer.

    STX = 0x02 · ETX = 0x03
    on the wire:  [0x02] <ZapPacket ...>…</ZapPacket> [0x03]

No length prefix anywhere. The listener answers with a ZapPacket of Type="ACK",
echoing the inbound Id — and it sends that ACK only after the capture is durably
stored, because the acknowledgement is a durability receipt and not a courtesy.

    ./send-plate.py --plate T-DEMO-01
    ./send-plate.py --plate T-DEMO-02 --repeat 2   # the same EventGuid twice
"""

import argparse
import socket
import sys
import uuid

STX = b"\x02"
ETX = b"\x03"


def packet(lane: str, event_guid: str, plate: str, confidence: str, camera: str) -> bytes:
    body = (
        f'<ZapPacket Type="MSG" Id="pkt-{event_guid}" Version="4.4" '
        f'SenderId="{camera}" SenderName="{camera}">'
        "<Event>"
        f"<EventId>1</EventId><EventGuid>{event_guid}</EventGuid>"
        "<Online>true</Online><TimeStamp>2026-08-07T09:00:00</TimeStamp>"
        f"<LaneId>{lane}</LaneId><LaneName>{lane}</LaneName>"
        # Two plate hypotheses. The listener takes the HIGHEST Confidence, which is
        # the second one — a reader that took the first would put the wrong truck
        # through the gate and pass every single-plate test.
        "<LP><AutoLPR>WRONG-1</AutoLPR><Confidence>0.41</Confidence></LP>"
        f"<LP><AutoLPR>{plate}</AutoLPR><Confidence>{confidence}</Confidence>"
        "<CharConfidence>0.93</CharConfidence>"
        # Images travel as filesystem PATHS, never as bytes (§D2).
        f'<LPRImage TIN="1" CameraId="{camera}"><Path>/var/lpr/demo.jpg</Path></LPRImage>'
        "</LP>"
        "</Event></ZapPacket>"
    ).encode("utf-8")
    return STX + body + ETX


def read_frame(sock: socket.socket) -> str | None:
    """Read one STX/ETX-delimited frame, or None if the listener says nothing."""
    buffer = bytearray()
    started = False
    while True:
        try:
            chunk = sock.recv(4096)
        except socket.timeout:
            return None
        if not chunk:
            return None
        for byte in chunk:
            if byte == STX[0]:
                started, buffer = True, bytearray()
            elif byte == ETX[0] and started:
                return buffer.decode("utf-8", "replace")
            elif started:
                buffer.append(byte)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=9100)
    parser.add_argument("--lane", default="LANE-DEMO-01")
    parser.add_argument("--plate", default="T-DEMO-01")
    parser.add_argument("--camera", default="DEV-DEMO-CAMERA")
    parser.add_argument("--confidence", default="0.94")
    parser.add_argument("--event-guid", default=None,
                        help="reuse one to show the dedup key doing its job")
    parser.add_argument("--repeat", type=int, default=1,
                        help="send the SAME packet this many times on one connection")
    arguments = parser.parse_args()

    event_guid = arguments.event_guid or f"evt-{uuid.uuid4()}"
    frame = packet(arguments.lane, event_guid, arguments.plate,
                   arguments.confidence, arguments.camera)

    print(f"→ lane {arguments.lane}, plate {arguments.plate}, EventGuid {event_guid}")

    with socket.create_connection((arguments.host, arguments.port), timeout=10) as sock:
        sock.settimeout(10)
        for attempt in range(arguments.repeat):
            sock.sendall(frame)
            answer = read_frame(sock)
            if answer is None:
                print(f"← (silence) — this instance does not own lane {arguments.lane}, "
                      "or there is no such lane at this site")
                return 2
            print(f"← {answer}")
            if 'Type="NAK"' in answer:
                return 3
            if attempt == 0 and arguments.repeat > 1:
                print("  (the same EventGuid again — the buffer keeps one row)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
