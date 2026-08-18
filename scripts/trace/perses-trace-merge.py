#!/usr/bin/env python3
"""Merge the Perses engine trace, the wrapper log and the staged programs.

    perses-trace-merge.py \
        --engine  trace_out/engine.ndjson \
        --wrapper trace_out/wrapper.ndjson \
        --staging trace_out/staging \
        --out     trace_out/reduction.json

Neither input alone is sufficient. The wrapper knows the program text and the
wall time but runs concurrently, so it cannot know the reduction order, the
token counts, the reducer, or whether its candidate was accepted. The engine
trace knows all of that but never sees the program text. They join on
``workDirId`` -- the per-candidate directory Perses creates, which is also the
test script's own $PWD.

All expensive work lives here rather than in the wrapper: hashing, content
addressing, diffing and character/line counting are deliberately kept off the
reduction's critical path so they cannot inflate the measured test time.
"""

import argparse
import datetime
import difflib
import hashlib
import json
import os
import sys
from collections import OrderedDict

SCHEMA_VERSION = "1.1.0"
CANDIDATE_KINDS = ("TEST_EXECUTION", "CACHE_HIT", "CANCELLED")
INVALID_SYNTAX_EXIT_CODE = 99


def read_ndjson(path):
    records = []
    if not path or not os.path.exists(path):
        return records
    with open(path, "r", encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as error:
                print("warning: skipping {}:{}: {}".format(path, number, error), file=sys.stderr)
    return records


def load_staged_programs(staging_dir, wrapper_basename):
    """Reads each staged candidate directory into a single text blob."""
    programs = {}
    if not staging_dir or not os.path.isdir(staging_dir):
        return programs
    for work_dir_id in sorted(os.listdir(staging_dir)):
        candidate_dir = os.path.join(staging_dir, work_dir_id)
        if not os.path.isdir(candidate_dir):
            continue
        chunks = []
        for root, _, files in os.walk(candidate_dir):
            for name in sorted(files):
                # The wrapper copies itself along with the candidate; skip it.
                if name == wrapper_basename:
                    continue
                full = os.path.join(root, name)
                try:
                    with open(full, "r", encoding="utf-8", errors="replace") as handle:
                        chunks.append(handle.read())
                except OSError:
                    continue
        if chunks:
            programs[work_dir_id] = "".join(chunks)
    return programs


def classify(edit_class, description):
    """Derives a transformation taxonomy from the raw evidence.

    This lives here, not in the Kotlin listener, because the description strings
    are unversioned free-form text with no test pinning them; matching on them
    inside Perses would break silently against upstream rewording.
    """
    description = description or ""
    if description.startswith("LPR transformation"):
        return "LLM"
    if edit_class == "LatraGeneralTreeEdit":
        return "LATRA"
    if edit_class == "DescendantHoistingTreeEdit":
        return "HOIST"
    if edit_class == "AnyNodeReplacementTreeEdit":
        return "REPLACE"
    if edit_class == "NodeDeletionTreeEdit":
        if description.startswith("token slicer@"):
            return "TOKEN_SLICE"
        if description.startswith("line slicer@"):
            return "LINE_SLICE"
        if description.startswith("list minimizer@"):
            return "LIST_MINIMISE"
        if "dd@" in description:
            return "DELTA_DEBUG"
        return "DELETE"
    return "UNKNOWN"


def candidate_status(engine):
    kind = engine.get("eventKind")
    if kind == "CACHE_HIT":
        return "CACHE_HIT"
    if kind == "CANCELLED":
        return "CANCELLED"
    if engine.get("isInteresting"):
        return "INTERESTING"
    if engine.get("exitCode") == INVALID_SYNTAX_EXIT_CODE:
        # Assigned by Perses' parsability postcheck after the script returns, so
        # the wrapper can only ever report this candidate as a plain failure.
        return "INVALID"
    return "REJECTED"


class ProgramPool(object):
    """Content-addressed store, so steps[] and candidates[] never duplicate text."""

    def __init__(self):
        self._by_digest = OrderedDict()

    def intern(self, text):
        if text is None:
            return None
        digest = hashlib.sha1(text.encode("utf-8")).hexdigest()
        if digest not in self._by_digest:
            self._by_digest[digest] = OrderedDict(
                [
                    ("id", "p_{}".format(digest[:8])),
                    ("text", text),
                    ("lines", text.count("\n")),
                    ("chars", len(text)),
                    ("referencedBySteps", False),
                ]
            )
        return self._by_digest[digest]["id"]

    def mark_step_program(self, program_id):
        for entry in self._by_digest.values():
            if entry["id"] == program_id:
                entry["referencedBySteps"] = True
                return

    def get_text(self, program_id):
        for entry in self._by_digest.values():
            if entry["id"] == program_id:
                return entry["text"]
        return None

    def entry(self, program_id):
        for entry in self._by_digest.values():
            if entry["id"] == program_id:
                return entry
        return None

    def emit(self, include_all):
        out = OrderedDict()
        for entry in self._by_digest.values():
            if not include_all and not entry["referencedBySteps"]:
                continue
            out[entry["id"]] = OrderedDict(
                [("text", entry["text"]), ("lines", entry["lines"]), ("chars", entry["chars"])]
            )
        return out

    def total_text_bytes(self):
        return sum(len(e["text"]) for e in self._by_digest.values())


def check_no_duplicate_join_keys(wrapper_records):
    """Refuses to merge a trace directory that holds more than one run.

    workDirId is only unique *within* a run: Perses restarts its candidate
    directory sequence at 000000 every time, while the wrapper log and the
    staging directory are append-only. Merging two runs would therefore join
    records to the wrong candidates and silently produce a plausible-looking but
    wrong trace, so this is a hard error rather than a warning.
    """
    seen = {}
    duplicates = []
    for record in wrapper_records:
        work_dir_id = record.get("workDirId")
        if work_dir_id is None:
            continue
        if work_dir_id in seen:
            duplicates.append(work_dir_id)
        seen[work_dir_id] = record
    if duplicates:
        sample = sorted(set(duplicates))[:5]
        sys.exit(
            "error: {} duplicate workDirId(s) in the wrapper log (e.g. {}).\n"
            "       The trace directory holds more than one Perses run. workDirId is only\n"
            "       unique within a run, so merging these would join records to the wrong\n"
            "       candidates.\n"
            "       Fix: delete the trace directory and re-run Perses into a clean one.".format(
                len(duplicates), ", ".join(sample)
            )
        )


def build(engine_records, wrapper_records, staged, args):
    check_no_duplicate_join_keys(wrapper_records)
    wrapper_by_id = {r["workDirId"]: r for r in wrapper_records if r.get("workDirId")}

    # `seq` is assigned on the engine's single dispatch thread, so it -- not the
    # wrapper's arrival order -- is the authoritative reduction order.
    engine_records.sort(key=lambda r: r.get("seq", 0))

    accepted_edit_ids = set()
    reduction_end = None
    for record in engine_records:
        kind = record.get("eventKind")
        if kind == "BEST_UPDATE" and record.get("editId") is not None:
            accepted_edit_ids.add(record["editId"])
        elif kind == "REDUCTION_END":
            reduction_end = record

    pool = ProgramPool()
    # The original program is never a candidate, so it is not in staging. Seeding
    # it gives the first accepted step a diff base like every later step has.
    original_program_id = None
    if args.original_file and os.path.exists(args.original_file):
        with open(args.original_file, "r", encoding="utf-8", errors="replace") as handle:
            original_program_id = pool.intern(handle.read())
        pool.mark_step_program(original_program_id)

    matched_ids = set()
    candidates = []
    steps = []

    original_tokens = reduction_end.get("originalTokens") if reduction_end else None
    best_tokens = original_tokens
    best_program_id = original_program_id
    tests_run = 0
    test_time_ms = 0
    overhead_ms = 0
    cache_hits = 0
    reducer_plan = []

    for engine in engine_records:
        kind = engine.get("eventKind")
        if kind not in CANDIDATE_KINDS:
            continue

        work_dir_id = engine.get("workDirId")
        wrapper = wrapper_by_id.get(work_dir_id) if work_dir_id else None
        if wrapper is not None:
            matched_ids.add(work_dir_id)
            overhead_ms += wrapper.get("wrapperOverheadMs") or 0

        if kind == "TEST_EXECUTION":
            tests_run += 1
            test_time_ms += max(engine.get("elapsedMillis") or 0, 0)
        elif kind == "CACHE_HIT":
            cache_hits += 1

        reducer = engine.get("reducerShortName")
        if reducer and (not reducer_plan or reducer_plan[-1] != reducer):
            reducer_plan.append(reducer)

        if original_tokens is None and engine.get("tokensBefore") is not None:
            original_tokens = engine["tokensBefore"]
            best_tokens = original_tokens

        edit_id = engine.get("editId")
        became_best = edit_id is not None and edit_id in accepted_edit_ids

        text = staged.get(work_dir_id) if work_dir_id else None
        program_ref = pool.intern(text)

        transformation = OrderedDict(
            [
                ("editClass", engine.get("editClass")),
                ("actionsDescription", engine.get("actionsDescription")),
                ("type", classify(engine.get("editClass"), engine.get("actionsDescription"))),
            ]
        )

        metrics = OrderedDict()
        metrics["testDurationMs"] = (
            wrapper.get("testDurationMs") if wrapper else engine.get("elapsedMillis")
        )
        metrics["engineReportedMs"] = engine.get("elapsedMillis")
        metrics["wrapperOverheadMs"] = wrapper.get("wrapperOverheadMs") if wrapper else None
        metrics["cumulativeTests"] = tests_run
        entry = pool.entry(program_ref) if program_ref else None
        metrics["chars"] = entry["chars"] if entry else None
        metrics["lines"] = entry["lines"] if entry else None

        if wrapper is not None:
            source = "BOTH"
        elif kind == "CACHE_HIT":
            source = "LISTENER_ONLY"
        else:
            source = "LISTENER_ONLY"

        candidate = OrderedDict()
        candidate["index"] = len(candidates)
        candidate["seq"] = engine.get("seq")
        candidate["workDirId"] = work_dir_id
        candidate["eventKind"] = kind
        candidate["status"] = candidate_status(engine)
        candidate["becameBest"] = became_best
        candidate["exitCode"] = engine.get("exitCode")
        candidate["reducer"] = reducer
        candidate["reducerPass"] = engine.get("reducerPass")
        candidate["editId"] = edit_id
        candidate["transformation"] = transformation
        candidate["targets"] = engine.get("targets")
        candidate["tokensBefore"] = engine.get("tokensBefore")
        candidate["tokensAfter"] = engine.get("tokensAfter")
        candidate["programRef"] = program_ref
        candidate["baseRef"] = best_program_id
        candidate["patch"] = None
        candidate["metrics"] = metrics
        candidate["provenance"] = OrderedDict(
            [
                ("workDirId", work_dir_id),
                ("matched", wrapper is not None),
                ("source", source),
                (
                    "note",
                    None
                    if wrapper is not None
                    else (
                        "query-cache hit; no script executed"
                        if kind == "CACHE_HIT"
                        else "no wrapper record; likely a global-execution-cache hit"
                    ),
                ),
            ]
        )
        candidates.append(candidate)

        if became_best:
            pool.mark_step_program(program_ref)
            before_text = pool.get_text(best_program_id) if best_program_id else None
            after_text = pool.get_text(program_ref) if program_ref else None
            tokens_after = engine.get("tokensAfter")

            step = OrderedDict()
            step["index"] = len(steps)
            step["candidateIndex"] = candidate["index"]
            step["programRef"] = program_ref
            step["baseRef"] = best_program_id
            step["tokensBefore"] = best_tokens
            step["tokensAfter"] = tokens_after
            if best_tokens is not None and tokens_after is not None:
                step["tokensRemoved"] = best_tokens - tokens_after
                step["reductionRatio"] = (
                    round((best_tokens - tokens_after) / float(best_tokens), 6)
                    if best_tokens
                    else 0.0
                )
            if original_tokens and tokens_after is not None:
                step["cumulativeReductionRatio"] = round(
                    (original_tokens - tokens_after) / float(original_tokens), 6
                )
            step["reducer"] = reducer
            step["reducerPass"] = engine.get("reducerPass")
            step["transformation"] = transformation
            step["targets"] = engine.get("targets")
            step["testsRunSoFar"] = tests_run
            step["elapsedMs"] = metrics["testDurationMs"]
            if before_text is not None and after_text is not None:
                step["diff"] = "".join(
                    difflib.unified_diff(
                        before_text.splitlines(keepends=True),
                        after_text.splitlines(keepends=True),
                        fromfile="before",
                        tofile="after",
                    )
                )
            steps.append(step)

            if program_ref is not None:
                best_program_id = program_ref
            if tokens_after is not None:
                best_tokens = tokens_after

    # Rejected candidates carry a patch against the best program at the time,
    # rather than their own full text, so the output stays bounded.
    if not args.debug_full_programs:
        for candidate in candidates:
            if candidate["becameBest"] or candidate["programRef"] is None:
                continue
            base_text = pool.get_text(candidate["baseRef"]) if candidate["baseRef"] else None
            cand_text = pool.get_text(candidate["programRef"])
            if base_text is not None and cand_text is not None:
                candidate["patch"] = "".join(
                    difflib.unified_diff(
                        base_text.splitlines(keepends=True),
                        cand_text.splitlines(keepends=True),
                        fromfile="best",
                        tofile="candidate",
                    )
                )
            candidate["programRef"] = None

    final_tokens = reduction_end.get("finalTokens") if reduction_end else best_tokens
    rejected = sum(1 for c in candidates if c["status"] in ("REJECTED", "INVALID"))

    summary = OrderedDict()
    summary["originalTokens"] = original_tokens
    summary["finalTokens"] = final_tokens
    summary["reductionRatio"] = (
        round((original_tokens - final_tokens) / float(original_tokens), 6)
        if original_tokens and final_tokens is not None
        else None
    )
    summary["totalCandidates"] = len(candidates)
    summary["acceptedSteps"] = len(steps)
    summary["rejectedCandidates"] = rejected
    summary["queryCacheHits"] = cache_hits
    summary["wallTimeMs"] = reduction_end.get("wallTimeMillis") if reduction_end else None
    summary["testTimeMs"] = test_time_ms
    summary["instrumentationOverheadMs"] = overhead_ms
    summary["scriptExecutions"] = tests_run
    summary["engineScriptExecutions"] = (
        reduction_end.get("scriptExecutionNumber") if reduction_end else None
    )
    summary["globalCacheHits"] = (
        reduction_end.get("externalCacheHitNumber") if reduction_end else None
    )
    summary["reducerPasses"] = reduction_end.get("reducerPasses") if reduction_end else None
    summary["wrapperRecords"] = len(wrapper_records)
    summary["joinedRecords"] = len(matched_ids)
    # Sanity check, creduce and the final formatter all run the script without
    # emitting a listener event, so a small non-zero value here is expected.
    summary["unmatchedWrapperLines"] = len(wrapper_by_id) - len(matched_ids)

    meta = OrderedDict()
    meta["tool"] = "perses"
    meta["toolVersion"] = args.tool_version
    meta["language"] = args.language
    meta["sourceFile"] = args.source_file
    meta["testScript"] = args.test_script
    meta["generatedAt"] = (
        datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()
    )
    meta["reducerPlan"] = reducer_plan

    return (
        OrderedDict(
            [
                ("schemaVersion", SCHEMA_VERSION),
                ("domain", "program-reduction"),
                ("meta", meta),
                ("summary", summary),
                ("originalProgramRef", original_program_id),
                ("programs", pool.emit(include_all=args.debug_full_programs)),
                ("steps", steps),
                ("candidates", candidates),
            ]
        ),
        pool,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True)
    parser.add_argument("--wrapper", required=True)
    parser.add_argument("--staging", default=None, help="directory the wrapper staged programs in")
    parser.add_argument("--out", required=True)
    parser.add_argument("--language", default=None)
    parser.add_argument("--source-file", default=None)
    parser.add_argument("--test-script", default=None)
    parser.add_argument("--tool-version", default=None)
    parser.add_argument("--wrapper-basename", default="perses-trace-wrapper.sh")
    parser.add_argument(
        "--original-file",
        default=None,
        help="the unreduced input program, so the first step has a diff base",
    )
    parser.add_argument("--indent", type=int, default=2)
    parser.add_argument(
        "--debug-full-programs",
        action="store_true",
        help="DEBUG ONLY. Emit full text for every candidate instead of a patch. "
        "On a real benchmark this produces a file tens of gigabytes in size.",
    )
    args = parser.parse_args()

    staged = load_staged_programs(args.staging, args.wrapper_basename)
    result, pool = build(read_ndjson(args.engine), read_ndjson(args.wrapper), staged, args)

    if args.debug_full_programs:
        estimate_mb = pool.total_text_bytes() / (1024.0 * 1024.0)
        print(
            "WARNING: --debug-full-programs is a debugging aid, not a supported mode.\n"
            "         Estimated program text in the output: {:.1f} MiB "
            "(plus JSON escaping overhead).\n"
            "         The supported default stores full text only for accepted steps.".format(
                estimate_mb
            ),
            file=sys.stderr,
        )

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=args.indent)
        handle.write("\n")

    summary = result["summary"]
    print(
        "wrote {}: {} steps, {} candidates, {} programs, {}/{} joined, ratio {}".format(
            args.out,
            summary["acceptedSteps"],
            summary["totalCandidates"],
            len(result["programs"]),
            summary["joinedRecords"],
            summary["totalCandidates"],
            summary["reductionRatio"],
        )
    )


if __name__ == "__main__":
    main()
