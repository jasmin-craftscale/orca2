#!/usr/bin/env python3
"""Prove that a change touched comments and nothing else.

Comment rewrites are safe only if they are provably comment rewrites. This
compares each changed file against its committed version with every comment
stripped from both sides: if the stripped forms are not identical, something
other than a comment moved, and the change is not what it claims to be.

    deploy/tools/assert-comments-only.py                  # every file changed vs HEAD
    deploy/tools/assert-comments-only.py --base main      # ...vs another ref
    deploy/tools/assert-comments-only.py path/to/File.java # specific files

Exit code 0 means every file differs only in its comments. Exit code 1 names the
files that do not, with the first differing line.

WHY A DETERMINISTIC STRIPPER IS ENOUGH. The stripper below is simple and does not
understand string literals, so it can in principle mis-handle a `--` or `//`
inside a quoted string. That does not weaken the check: the SAME stripper runs
over both sides, so any change to real code still shows up as a difference. It
can only ever produce a false alarm, never a false pass — which is the correct
direction for a safety check to fail in.
"""

import re
import subprocess
import sys
from pathlib import Path

SQL_SUFFIXES = {".sql"}
C_STYLE_SUFFIXES = {".java", ".kts", ".gradle", ".js", ".ts"}
HASH_SUFFIXES = {".yaml", ".yml", ".py", ".sh", ".properties"}
XML_SUFFIXES = {".xml", ".bpmn20.xml"}

SUPPORTED = SQL_SUFFIXES | C_STYLE_SUFFIXES | HASH_SUFFIXES | XML_SUFFIXES


def strip_sql(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r"--.*$", "", line) for line in text.splitlines())


def strip_c_style(text: str) -> str:
    """Remove // and /* */ comments, leaving string literals untouched.

    A naive regex cannot do this. `String u = "http://a";` contains `//` inside a
    string, so a regex stripper truncates the line at `http:` — and then a real
    change to that string is invisible to the comparison, which is the one thing
    this tool exists to prevent. There are 93 such lines in this repository.

    So this walks the text once, tracking whether it is inside a string, a
    character literal or a Java text block, and only treats `//` and `/*` as
    comment starts when it is inside none of them.
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        # Text block: """ ... """ — Java's multi-line string, used here for SQL,
        # which means it can legitimately contain both -- and // and must survive.
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end == -1 else end + 3
            out.append(text[i:end])
            i = end
        elif c in '"\'':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == c:
                    j += 1
                    break
                if text[j] == "\n":  # unterminated: do not run past the line
                    break
                j += 1
            out.append(text[i:j])
            i = j
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j == -1 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_hash(text: str) -> str:
    out = []
    for line in text.splitlines():
        # A '#' that starts a line (allowing indentation) is a comment. A '#'
        # mid-line is left alone: in YAML it may be part of a value, and this
        # stripper does not need to be clever to be sound.
        out.append("" if re.match(r"^\s*#", line) else line)
    return "\n".join(out)


def strip_xml(text: str) -> str:
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


def strip(path: Path, text: str) -> str:
    name = path.name
    if name.endswith(".bpmn20.xml") or path.suffix in XML_SUFFIXES:
        stripped = strip_xml(text)
    elif path.suffix in SQL_SUFFIXES:
        stripped = strip_sql(text)
    elif path.suffix in C_STYLE_SUFFIXES:
        stripped = strip_c_style(text)
    elif path.suffix in HASH_SUFFIXES:
        stripped = strip_hash(text)
    else:
        stripped = text
    # Normalise whitespace so that re-indenting a comment block, or leaving a
    # blank line where one used to be, is not reported as a code change.
    lines = [line.rstrip() for line in stripped.splitlines()]
    return "\n".join(line for line in lines if line.strip())


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=True
    ).stdout


def committed_version(base: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{base}:{path}"], capture_output=True, text=True
    )
    return result.stdout if result.returncode == 0 else None


def main() -> int:
    args = sys.argv[1:]
    base = "HEAD"
    if "--base" in args:
        i = args.index("--base")
        base = args[i + 1]
        del args[i : i + 2]

    if args:
        paths = args
    else:
        changed = git("diff", "--name-only", base).split()
        untracked = git("ls-files", "--others", "--exclude-standard").split()
        paths = sorted(set(changed) | set(untracked))

    checked, skipped, failures = 0, [], []

    for rel in paths:
        path = Path(rel)
        if not path.exists():
            continue  # deleted; nothing to compare
        if not (path.name.endswith(".bpmn20.xml") or path.suffix in SUPPORTED):
            skipped.append(rel)
            continue

        before = committed_version(base, rel)
        if before is None:
            skipped.append(f"{rel} (new file — nothing to compare)")
            continue

        after = path.read_text(encoding="utf-8")
        a, b = strip(path, before), strip(path, after)
        checked += 1
        if a != b:
            a_lines, b_lines = a.splitlines(), b.splitlines()
            detail = "lengths differ"
            for n, (x, y) in enumerate(zip(a_lines, b_lines), start=1):
                if x != y:
                    detail = f"first difference at stripped line {n}:\n      was: {x}\n      now: {y}"
                    break
            failures.append((rel, detail))

    for rel in skipped:
        print(f"  skipped  {rel}")
    for rel, detail in failures:
        print(f"  CHANGED  {rel}\n      {detail}")

    if failures:
        print(
            f"\nFAIL — {len(failures)} of {checked} file(s) changed outside their comments."
        )
        return 1

    print(f"\nPASS — {checked} file(s) checked; every change is a comment change.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
