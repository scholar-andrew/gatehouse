"""Writes docs/version-history.md: every version of the program (Gatehouse and Gatekeeper) with its notes, newest first,
from source/gatehouse.json and source/gatekeeper.json. Before 3.0.0 the two halves had versions of their own; a version
in both with the same notes is listed once. Run by tools/build_pack.py; also on its own: python tools/version_history.py"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def key(v):
    out = []
    for p in v.split("."):
        n = "".join(c for c in p if c.isdigit())
        out.append(int(n) if n else 0)
    return out


def write(root=ROOT):
    with open(os.path.join(root, "source", "gatehouse.json"), encoding="utf-8") as f:
        house = json.load(f)["changes"]
    with open(os.path.join(root, "source", "gatekeeper.json"), encoding="utf-8") as f:
        keeper = json.load(f)["changes"]
    rows = {}
    for who, changes in (("Gatehouse", house), ("Gatekeeper", keeper)):
        for c in changes:
            v = c["version"]
            notes = tuple(c.get("notes", []))
            had = rows.setdefault(v, [])
            for h in had:
                if h["notes"] == notes:
                    h["who"].append(who)
                    break
            else:
                had.append({"who": [who], "date": c.get("date", ""), "notes": notes})
    lines = ["# Version history", "",
             "Every version of the program, newest first, with the notes its Updates section shows. Since 3.0.0 Gatehouse",
             "and Gatekeeper are one program with one version; before that each half had its own. The October Journey",
             "pack's own versions are in [CHANGELOG.md](../CHANGELOG.md).", "",
             "This page is written by `tools/build_pack.py` from `source/gatehouse.json` and `source/gatekeeper.json`; edit",
             "those, not this.", ""]
    for v in sorted(rows, key=key, reverse=True):
        for h in rows[v]:
            # (one program since 3.0.0: named only where the two files say different things for one version)
            whom = "" if len(rows[v]) == 1 and len(h["who"]) == 2 else " (" + " and ".join(h["who"]) + ")"
            lines.append("## " + v + whom + (" - " + h["date"] if h["date"] else ""))
            lines.append("")
            for n in h["notes"]:
                lines.append("- " + n)
            lines.append("")
    with open(os.path.join(root, "docs", "version-history.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    write()
    print("docs/version-history.md written")
