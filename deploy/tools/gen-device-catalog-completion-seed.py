#!/usr/bin/env python3
"""Generate the Phase 3 WP0 device-catalog completion from docs/device-catalog-completion-from-1x.md.

Zero hand transcription, same discipline as gen-entitlement-seed.py: this script
parses the committed completion file (itself script-read from 1.x's migration.go),
verifies the counts the sheet states (19 io_device_kind / 5 Audio port names /
16 device types), and emits the SQL body of V108 with:
  - pinned UUIDs: UUIDv5 in the SAME namespace V106's seeds used
    (uuid5(NAMESPACE_URL, 'orca:2.0:device-catalog'), keyed '<table>:<code>'),
    so a regenerated seed is byte-identical and a corrected code gets the
    external id that code would always have had
  - stable machine codes carried verbatim from the sheet (already UPPER_SNAKE)
  - display names carried display-only, as-is from 1.x

Where V106 already seeded a row, this emits an UPDATE rather than a second
INSERT: the three PTZ camera codes V106 marked provisional are corrected to the
1.x-exact codes (external id follows the code — identity is the code, rule 7),
and display names move to the 1.x-verbatim casing.
"""
import re
import sys
import uuid
from pathlib import Path

SHEET = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/device-catalog-completion-from-1x.md")

# The SAME namespace V106's device-catalog seeds were minted in. Verified against
# the shipped rows: uuid5(this, 'device_type:AXIS_CAMERA') == 0751b608-4796-…
NS = uuid.uuid5(uuid.NAMESPACE_URL, "orca:2.0:device-catalog")

# What V106 already seeded, keyed by the sheet's (1.x-exact) code:
#   value = the code the row currently carries in V106.
# Three were provisional guesses and are corrected in place; the rest match.
V106_DEVICE_TYPE_CODES = {
    "AXIS_CAMERA": "AXIS_CAMERA",
    "AXIS_PTZ_CAMERA": "PTZ_CAMERA",              # provisional in V106
    "PELCO_PTZ_CAMERA": "PELCO_CAMERA",           # provisional in V106
    "MILESIGHT_PTZ_CAMERA": "MILESIGHT_CAMERA",   # provisional in V106
    "LPR_CAMERA": "LPR_CAMERA",
    "BARCODE_SCANNER": "BARCODE_SCANNER",
    "RFID": "RFID",
    "GATE_ARM": "GATE_ARM",
    "SCALE": "SCALE",
    "PRINTER": "PRINTER",
    "PORTAL_SCAN": "PORTAL_SCAN",
    "EDGE_DEVICE_DISPLAY": "EDGE_DEVICE_DISPLAY",
}

# The one audio port name V106 could already seed — via the sheet's naming-drift
# note, as the phase-2 report records ("four of the five audio names are
# unextracted"). The completion sheet's heading calls all five "missing", which
# is off by one against the shipped V106; reported in the phase-3 report rather
# than silently smoothed. Here it simply means: 4 INSERTs, not 5.
V106_PORT_NAME_CODES = {"FRONT_MIC"}


def mint(table: str, code: str) -> str:
    return str(uuid.uuid5(NS, f"{table}:{code}"))


def code_token(display: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", display.strip()).strip("_").upper()


text = SHEET.read_text()


def table_rows(section_heading: str, columns: int):
    """The markdown table under a `## ` heading: list of cell tuples."""
    section = text.split(section_heading, 1)[1]
    rows = []
    for line in section.splitlines():
        if line.startswith("## "):
            break
        m = re.match(r"^\|" + r"([^|]+)\|" * columns + r"\s*$", line)
        if not m:
            continue
        cells = tuple(c.strip() for c in m.groups())
        if any(c.startswith("---") for c in cells) or cells[0] in ("name", "port_type", "device_type_name"):
            continue
        rows.append(cells)
    return rows


kinds = table_rows("## `io_device_kind`", 3)          # (name, input_type, code)
audio = table_rows("## `device_io_port_name`", 2)      # (port_type, io_port_name)
types = table_rows("## `device_type`", 2)              # (device_type_name, code)

assert len(kinds) == 19, f"io_device_kind: sheet states 19, parsed {len(kinds)}"
assert len(audio) == 5, f"audio port names: sheet states 5, parsed {len(audio)}"
assert len(types) == 16, f"device_type: sheet states 16, parsed {len(types)}"
assert all(pt == "Audio" for pt, _ in audio), "the port-name top-up is the Audio group only"

out = []

out.append("-- device_type: correct the three provisional PTZ codes to the 1.x-exact codes.")
out.append("-- The external id FOLLOWS the code (identity is the code, translation rule 7),")
out.append("-- so each corrected row gets the UUID its code would always have minted.")
for sheet_code, v106_code in V106_DEVICE_TYPE_CODES.items():
    if sheet_code == v106_code:
        continue
    name = next(n for n, c in types if c == sheet_code)
    out.append(
        f"UPDATE device_type SET code = '{sheet_code}', external_id = '{mint('device_type', sheet_code)}', "
        f"name = N'{name}' WHERE code = '{v106_code}';"
    )

out.append("")
out.append("-- device_type: move the already-correct rows' display names to the 1.x-verbatim casing")
out.append("-- (display-only by rule 7; V106 carried them lowercase-provisional).")
for name, code in types:
    v106_code = V106_DEVICE_TYPE_CODES.get(code)
    if v106_code is None or v106_code != code:
        continue
    out.append(f"UPDATE device_type SET name = N'{name}' WHERE code = '{code}';")

out.append("")
out.append("-- device_type: the four rows the V106 sheet could not name (16 total).")
for name, code in types:
    if code in V106_DEVICE_TYPE_CODES:
        continue
    out.append(
        f"INSERT INTO device_type (external_id, code, name) VALUES ('{mint('device_type', code)}', "
        f"'{code}', N'{name}');"
    )

out.append("")
out.append("-- device_io_port_name: the four Audio rows Phase 2 could not extract (40 total;")
out.append("-- FRONT_MIC was already seeded by V106 via the sheet's naming-drift note).")
for _, name in audio:
    code = code_token(name)
    if code in V106_PORT_NAME_CODES:
        continue
    out.append(
        f"INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES "
        f"('{mint('device_io_port_name', code)}', 'AUDIO', '{code}', N'{name}');"
    )

out.append("")
out.append("-- io_device_kind: all 19 rows (created to shape in V106, seeded empty).")
for name, input_type, code in kinds:
    port_type = input_type.strip().upper()
    out.append(
        f"INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES "
        f"('{mint('io_device_kind', code)}', '{port_type}', '{code}', N'{name}');"
    )

print("\n".join(out))
