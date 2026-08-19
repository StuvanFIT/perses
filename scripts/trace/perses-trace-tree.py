#!/usr/bin/env python3
"""Render the reduction search tree from a reduction.json.

    perses-trace-tree.py reduction.json --format mermaid > tree.mmd
    perses-trace-tree.py reduction.json --format dot | dot -Tsvg -o tree.svg
    perses-trace-tree.py reduction.json --format text

The tree this builds is the SEARCH tree, not the syntax tree:

  * a node is a program state (an entry in `programs`)
  * a solid edge is an ACCEPTED candidate -- it advanced the reduction
  * a dashed edge is a REJECTED candidate -- a dead end explored and abandoned

The trunk from the root to the last node is exactly `steps[]`. Everything
branching off it is work that did not pay off, which is most of a reduction.

This is derivable because every candidate records `baseRef` (the program it was
built from). The syntax tree Perses edits internally is NOT derivable from this
schema: `targets[]` carries node ids but no parent/child relationships.
"""

import argparse
import json
import sys
from collections import OrderedDict, defaultdict


def build_tree(doc, collapse):
    """Replays the run in `seq` order and hangs each dead end off the state that was
    current when it was tried.

    Identity here is the POSITION in the trajectory, not the programRef. Programs are
    content-addressed, so two different edits that yield identical text share a ref --
    and a token-neutral step can even produce text equal to something seen earlier.
    Keying the trunk on programRef therefore silently merges distinct states and loses
    steps. Position is unambiguous.
    """
    programs = doc["programs"]
    root = doc.get("originalProgramRef")

    # Trunk state i is the program *before* step i; the last state is the final program.
    states = []
    first_base = doc["steps"][0]["baseRef"] if doc["steps"] else root
    states.append({"pos": 0, "ref": first_base or root,
                   "tokens": doc["steps"][0]["tokensBefore"] if doc["steps"] else None,
                   "step_out": None, "failures": []})
    for step in doc["steps"]:
        states[-1]["step_out"] = step
        states.append({"pos": len(states), "ref": step["programRef"],
                       "tokens": step["tokensAfter"], "step_out": None, "failures": []})

    # A candidate belongs to whichever state was current when it ran. Steps carry
    # candidateIndex, so the boundaries are exactly the accepted candidates.
    boundary = {}
    for step in doc["steps"]:
        boundary[step["candidateIndex"]] = step["index"]

    current = 0
    for cand in sorted(doc["candidates"], key=lambda c: c.get("seq", 0)):
        if cand["becameBest"]:
            current = boundary.get(cand["index"], current) + 1
            continue
        states[min(current, len(states) - 1)]["failures"].append(cand)

    return {"root": root, "states": states, "programs": programs, "collapse": collapse}


def label_for_program(t, ref, tokens):
    entry = t["programs"].get(ref) or {}
    lines = entry.get("lines")
    return "%s\\n%s tokens, %s lines" % (ref, tokens, lines)


def summarise_failures(cands):
    """Groups dead ends by transformation type so the diagram stays readable."""
    buckets = OrderedDict()
    for c in cands:
        key = (c["transformation"]["type"], c["status"])
        buckets.setdefault(key, []).append(c)
    return buckets


def _node_id(st):
    return "s%d" % st["pos"]


def _node_label(t, st):
    entry = t["programs"].get(st["ref"]) or {}
    return "%s\\n%s tokens, %s lines" % (st["ref"], st["tokens"], entry.get("lines", "?"))


def emit_mermaid(t):
    out = ["graph TD",
           "  classDef prog fill:#0d47a1,stroke:#1565c0,color:#fff;",
           "  classDef failed fill:#3e2723,stroke:#6d4c41,color:#fff;"]
    for st in t["states"]:
        out.append('  %s["%s"]:::prog' % (_node_id(st), _node_label(t, st).replace("\\n", "<br/>")))
    for st in t["states"]:
        step = st["step_out"]
        if step:
            out.append('  %s ==>|"step %s: %s<br/>-%s tokens"| %s'
                       % (_node_id(st), step["index"], step["transformation"]["type"],
                          step.get("tokensRemoved", 0), "s%d" % (st["pos"] + 1)))
        if t["collapse"]:
            for (ttype, status), grp in summarise_failures(st["failures"]).items():
                fid = "f%d_%s_%s" % (st["pos"], ttype, status)
                out.append('  %s["%s x%d<br/>%s"]:::failed' % (fid, ttype, len(grp), status))
                out.append("  %s -.-> %s" % (_node_id(st), fid))
        else:
            for c in st["failures"]:
                fid = "c%d" % c["index"]
                out.append('  %s["#%s %s<br/>%s"]:::failed'
                           % (fid, c["index"], c["transformation"]["type"], c["status"]))
                out.append("  %s -.-> %s" % (_node_id(st), fid))
    return "\n".join(out)


def emit_dot(t):
    out = ["digraph reduction {", "  rankdir=TB;",
           '  node [shape=box, style=filled, fontname="Helvetica"];']
    for st in t["states"]:
        out.append('  %s [label="%s", fillcolor="#0d47a1", fontcolor=white];'
                   % (_node_id(st), _node_label(t, st)))
    for st in t["states"]:
        step = st["step_out"]
        if step:
            out.append('  %s -> s%d [label="step %s: %s (-%s)", penwidth=2, color="#2e7d32"];'
                       % (_node_id(st), st["pos"] + 1, step["index"],
                          step["transformation"]["type"], step.get("tokensRemoved", 0)))
        for (ttype, status), grp in summarise_failures(st["failures"]).items():
            fid = "f%d_%s_%s" % (st["pos"], ttype, status)
            out.append('  %s [label="%s x%d\\n%s", fillcolor="#4e342e", fontcolor=white];'
                       % (fid, ttype, len(grp), status))
            out.append("  %s -> %s [style=dashed, color=\"#8d6e63\"];" % (_node_id(st), fid))
    out.append("}")
    return "\n".join(out)


def emit_text(t):
    lines = []
    for st in t["states"]:
        lines.append("%s  (%s tokens)" % (st["ref"], st["tokens"]))
        for (ttype, status), grp in summarise_failures(st["failures"]).items():
            lines.append("  |  x  %-14s %-11s x%d" % (ttype, status, len(grp)))
        step = st["step_out"]
        if step:
            lines.append("  |")
            lines.append("  +->  step %-2s %-14s -%s tokens"
                         % (step["index"], step["transformation"]["type"],
                            step.get("tokensRemoved", 0)))
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("reduction_json")
    ap.add_argument("--format", choices=("mermaid", "dot", "text"), default="text")
    ap.add_argument("--no-collapse", action="store_true",
                    help="draw every dead end separately instead of grouping them")
    args = ap.parse_args()

    with open(args.reduction_json, encoding="utf-8") as fh:
        doc = json.load(fh)

    tree = build_tree(doc, collapse=not args.no_collapse)
    if not tree["root"]:
        sys.exit("error: no originalProgramRef; re-run the merge with --original-file")

    print({"mermaid": emit_mermaid, "dot": emit_dot, "text": emit_text}[args.format](tree))


if __name__ == "__main__":
    main()
