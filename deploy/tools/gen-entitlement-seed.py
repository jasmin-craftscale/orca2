#!/usr/bin/env python3
"""Generate the WP1 entitlement-catalog seed from docs/entitlement-catalog-from-1x.md.

Zero hand transcription: this script parses the committed catalog file (itself
script-extracted from 1.x), verifies the GATE counts the sheet states (3/30/174),
and emits INSERT statements with:
  - pinned UUIDs: re-minted deterministically as UUIDv5 in a fixed ORCA namespace
    derived from each node's stable code (translation rule 7 — 1.x UUIDs are
    provenance, not identity)
  - stable machine codes derived from the tree position
  - display names carried display-only

PWA tree (2/3/13) is deliberately NOT emitted — portal scope, deferred.
"""
import re
import sys
import uuid
from pathlib import Path

CATALOG = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/entitlement-catalog-from-1x.md")

# Fixed namespace so every regeneration of this seed yields identical UUIDs.
# uuid5(NAMESPACE_URL, 'orca:2.0:entitlement-catalog')
NS = uuid.uuid5(uuid.NAMESPACE_URL, "orca:2.0:entitlement-catalog")


def code_token(display: str) -> str:
    """SNAKE_CASE token from a display name; '&' -> AND, non-alnum -> _ ."""
    t = display.strip().replace("&", " AND ")
    t = re.sub(r"[^A-Za-z0-9]+", "_", t).strip("_").upper()
    return t


text = CATALOG.read_text()

# Only the GATE tree: cut at the PWA heading.
gate_text = text.split("\n## PWA", 1)[0]

modules = []  # (module_name, [ (submodule_name, [(action, routes)]) ])
current_module = None
current_sub = None

module_re = re.compile(r"^### GATE · (.+?)\s*$")
sub_re = re.compile(r"^\*\*(.+?)\*\* — \*1\.x")
row_re = re.compile(r"^\| (?!Action item)(?!---)(.+?) \| `(.+?)` \| `.+?` \|\s*$")

for line in gate_text.splitlines():
    m = module_re.match(line)
    if m:
        current_module = (m.group(1).strip(), [])
        modules.append(current_module)
        current_sub = None
        continue
    m = sub_re.match(line)
    if m and current_module is not None:
        current_sub = (m.group(1).strip(), [])
        current_module[1].append(current_sub)
        continue
    m = row_re.match(line)
    if m and current_sub is not None:
        current_sub[1].append((m.group(1).strip(), m.group(2).strip()))

n_modules = len(modules)
n_subs = sum(len(subs) for _, subs in modules)
n_items = sum(len(items) for _, subs in modules for _, items in subs)
print(f"-- parsed GATE: {n_modules} modules / {n_subs} sub-modules / {n_items} action items", file=sys.stderr)
assert n_modules == 3, f"expected 3 modules, parsed {n_modules}"
assert n_subs == 30, f"expected 30 sub-modules, parsed {n_subs}"
assert n_items == 174, f"expected 174 action items, parsed {n_items}"

# Build codes; disambiguate duplicates deterministically in document order (_2, _3 …).
def disambiguate(seq):
    seen = {}
    out = []
    for s in seq:
        n = seen.get(s, 0) + 1
        seen[s] = n
        out.append(s if n == 1 else f"{s}_{n}")
    return out


def u5(code: str) -> str:
    return str(uuid.uuid5(NS, code))


def esc(s: str) -> str:
    return s.replace("'", "''")


lines = []
app_code = "GATE"
lines.append(
    f"INSERT INTO entitlement_application (external_id, code, name) VALUES ('{u5(app_code)}', '{app_code}', N'GATE');"
)

dup_notes = []
mod_codes = disambiguate([code_token(mn) for mn, _ in modules])
for (mod_name, subs), mod_code_tok in zip(modules, mod_codes):
    mod_code = f"{app_code}.{mod_code_tok}"
    lines.append(
        "INSERT INTO entitlement_module (application_id, external_id, code, name)\n"
        f"SELECT application_id, '{u5(mod_code)}', '{mod_code}', N'{esc(mod_name)}' FROM entitlement_application WHERE code = '{app_code}';"
    )
    sub_toks = disambiguate([code_token(sn) for sn, _ in subs])
    for (sub_name, items), sub_tok in zip(subs, sub_toks):
        raw_tok = code_token(sub_name)
        if sub_tok != raw_tok:
            dup_notes.append(f"sub-module '{sub_name}' occurs more than once under module '{mod_name}' -> {sub_tok}")
        sub_code = f"{mod_code}.{sub_tok}"
        lines.append(
            "INSERT INTO entitlement_sub_module (module_id, external_id, code, name)\n"
            f"SELECT module_id, '{u5(sub_code)}', '{sub_code}', N'{esc(sub_name)}' FROM entitlement_module WHERE code = '{mod_code}';"
        )
        item_toks = disambiguate([code_token(an) for an, _ in items])
        for (act_name, routes), act_tok in zip(items, item_toks):
            raw_a = code_token(act_name)
            if act_tok != raw_a:
                dup_notes.append(f"action '{act_name}' occurs more than once under '{sub_name}' -> {act_tok}")
            act_code = f"{sub_code}.{act_tok}"
            lines.append(
                "INSERT INTO entitlement_action_item (sub_module_id, external_id, code, name, licence_route)\n"
                f"SELECT sub_module_id, '{u5(act_code)}', '{act_code}', N'{esc(act_name)}', '{esc(routes)}' FROM entitlement_sub_module WHERE code = '{sub_code}';"
            )

for n in dup_notes:
    print(f"-- DISAMBIGUATED: {n}", file=sys.stderr)

print("\n".join(lines))
print(f"-- rows: 1 application, {n_modules} modules, {n_subs} sub-modules, {n_items} action items", file=sys.stderr)
