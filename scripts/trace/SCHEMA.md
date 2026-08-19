# `reduction.json` Field Reference

Every field in the trace, and what it means when you are reducing a program.

All examples come from a real run that shrank a 20-line C file from **75 tokens
to 17**, testing **111 candidates** along the way.

---

## First, four ideas the whole file rests on

**Token.** Perses measures programs in grammar tokens, not lines or characters.
`int keep_me = 42;` is 5 tokens: `int`, `keep_me`, `=`, `42`, `;`. This is why
`originalTokens` won't match your editor's line count, and why a step can delete
a whole line without changing the token count much.

**Candidate vs. step.** A **candidate** is one variant Perses built and tested.
A **step** is a candidate that *worked* and became the new best program. In the
example: 111 candidates, 12 steps. Most candidates fail — that is normal and is
how reduction works.

**The spar-tree.** Perses parses your program into a syntax tree and deletes
*nodes*, not text. That is why it never produces garbage like an unmatched brace.
`targets[]` tells you which tree nodes a candidate attacked.

**The trace has two sources.** Perses records what it *did*; a wrapper around
your test script records what the test *saw*. They are matched by the temporary
directory name (`workDirId`). `provenance` tells you whether a record has both
halves.

---

## Top level

| Field | Meaning |
|---|---|
| `schemaVersion` | Format version, currently `"1.1.0"`. Check this before parsing. |
| `domain` | Always `"program-reduction"`. Marks the trace kind. |
| `meta` | What was reduced, with what, when |
| `summary` | Totals for the whole run |
| `originalProgramRef` | Key into `programs` for your **unreduced** input, e.g. `"p_7dec01b4"` |
| `programs` | Every distinct program text, stored once each |
| `steps` | The accepted reduction trajectory |
| `candidates` | Every variant tried, accepted or not |

---

## `meta`

```json
{
  "tool": "perses",
  "toolVersion": null,
  "language": "c",
  "sourceFile": "t.c",
  "testScript": "my_test.sh",
  "generatedAt": "2026-08-19T12:36:12+00:00",
  "reducerPlan": ["node_priority-dfs", "token_canonicalizer",
                  "node_priority-dfs", "token_canonicalizer",
                  "FineGritLatraReducer"]
}
```

| Field | Meaning |
|---|---|
| `tool` / `toolVersion` | The reducer. `toolVersion` is `null` unless supplied at merge time. |
| `language` | Detected from the file extension. Determines which grammar was used. |
| `sourceFile` | Base name of the input program |
| `testScript` | Base name of your interestingness test |
| `generatedAt` | When the **merge** ran (UTC), not when the reduction started |
| `reducerPlan` | The reducers that actually ran, **in order**, with repeats |

`reducerPlan` is observed, not configured — it is the real sequence. Repeats are
expected: Perses alternates strategies and revisits earlier ones as the program
shrinks. Above, `node_priority-dfs` ran, then canonicalisation, then both again,
then a Latra pass.

---

## `summary`

```json
{
  "originalTokens": 75,          "finalTokens": 17,
  "reductionRatio": 0.773333,
  "totalCandidates": 111,        "acceptedSteps": 12,
  "rejectedCandidates": 79,      "queryCacheHits": 18,
  "wallTimeMs": 23895,           "testTimeMs": 23134,
  "instrumentationOverheadMs": 1264,
  "scriptExecutions": 91,        "engineScriptExecutions": 94,
  "globalCacheHits": 0,          "reducerPasses": 5,
  "wrapperRecords": 93,          "joinedRecords": 92,
  "unmatchedWrapperLines": 1
}
```

### Size

| Field | Meaning |
|---|---|
| `originalTokens` | Token count of your input — `75` |
| `finalTokens` | Token count of the result — `17` |
| `reductionRatio` | Fraction **removed**: `(75-17)/75 = 0.773` → 77.3% smaller |

Note the direction: `0.773` means 77% was *deleted*, and 22.7% remains.

### Effort

| Field | Meaning |
|---|---|
| `totalCandidates` | Every variant recorded — `111` |
| `acceptedSteps` | Candidates that became the new best — `12` |
| `rejectedCandidates` | Tested and failed (`REJECTED` + `INVALID`) — `79` |
| `queryCacheHits` | Variants recognised as already-seen; your test was **not** run — `18` |
| `reducerPasses` | How many times a reducer was invoked — `5`, matching `reducerPlan` |

`12 + 79 + 18 = 109`; the remaining 2 were cancelled. A **~10:1 rejected-to-accepted
ratio is healthy** — reduction is search, and most probes miss.

### Time

| Field | Meaning |
|---|---|
| `wallTimeMs` | Real elapsed time — `23895` (~24s) |
| `testTimeMs` | Sum of every test execution — `23134` |
| `instrumentationOverheadMs` | Cost added by tracing — `1264` |

**`testTimeMs` can exceed `wallTimeMs`.** Perses runs tests in parallel, so summed
test time can exceed elapsed time. Don't compute "fraction of time spent testing"
from these two.

For tracing cost, use `instrumentationOverheadMs / testTimeMs` — here **5.5%**.
This is measured honestly: the timer brackets only the delegation to your test,
so staging and logging land in the overhead figure rather than inflating test time.

### Bookkeeping

These let you check nothing was lost.

| Field | Meaning |
|---|---|
| `scriptExecutions` | `TEST_EXECUTION` records in the trace — `91` |
| `engineScriptExecutions` | Perses' own counter — `94` |
| `globalCacheHits` | Cached verdicts reused across runs — `0` |
| `wrapperRecords` | Lines your test wrapper logged — `93` |
| `joinedRecords` | Candidates matched to a wrapper line — `92` |
| `unmatchedWrapperLines` | Wrapper lines with no Perses record — `1` |

**Why `91` vs `94`:** Perses counts a script execution before the test starts.
Some tasks get cancelled after their script ran but before a record was emitted.
`engineScriptExecutions` is an upper bound.

**`unmatchedWrapperLines: 1` is expected.** Perses runs your test once as a
safety check before reduction begins, outside the traced sequence. A count in the
dozens means something else is running your script uninstrumented.

**If `globalCacheHits > 0`, treat timings with care** — those candidates reused a
stored verdict, so their `elapsedMillis` is from an *earlier* run.

---

## `programs`

A content-addressed pool. Each key is `p_` plus the first 8 hex digits of the
text's SHA-1.

```json
"programs": {
  "p_7dec01b4": { "text": "#include <stdio.h>\n...", "lines": 20, "chars": 276 }
}
```

| Field | Meaning |
|---|---|
| `text` | The complete program source |
| `lines` / `chars` | Newline and character counts |

**Why a pool?** A program appears here once no matter how many records reference
it. `steps[]` and `candidates[]` hold `programRef` keys instead of copies. With
thousands of candidates this is the difference between megabytes and gigabytes.

**Only programs that became steps are stored in full.** Rejected candidates carry
a `patch` instead — reconstruct them by applying it to `baseRef`.

---

## `steps[]` — the reduction trajectory

The programs a user actually navigates: each one is smaller (or simpler) than the
last, and each is a valid, still-interesting program.

```json
{
  "index": 0,
  "candidateIndex": 4,
  "programRef": "p_2072ab4b",
  "baseRef": "p_7dec01b4",
  "tokensBefore": 75, "tokensAfter": 53, "tokensRemoved": 22,
  "reductionRatio": 0.293333,
  "cumulativeReductionRatio": 0.293333,
  "reducer": "node_priority-dfs",
  "reducerPass": 1,
  "transformation": { "editClass": "NodeDeletionTreeEdit",
                      "actionsDescription": "...dd@2", "type": "DELTA_DEBUG" },
  "targets": [ ... ],
  "testsRunSoFar": 5,
  "elapsedMs": 191,
  "diff": "--- before\n+++ after\n@@ -1,9 +1,4 @@\n..."
}
```

| Field | Meaning |
|---|---|
| `index` | Position in the trajectory, from `0` |
| `candidateIndex` | Index into `candidates[]` — the same event, fully detailed |
| `programRef` | The program **after** this step |
| `baseRef` | The program **before**. Step 0's is `originalProgramRef`. |
| `tokensBefore` / `tokensAfter` / `tokensRemoved` | Size change |
| `reductionRatio` | Fraction removed **by this step alone** |
| `cumulativeReductionRatio` | Fraction removed **since the original** |
| `reducer` | Which strategy found it |
| `reducerPass` | Which invocation of that reducer |
| `transformation` | What kind of edit — see below |
| `targets` | Which syntax-tree nodes were changed |
| `testsRunSoFar` | Candidates tested up to this point — the *cost* of reaching here |
| `elapsedMs` | How long this candidate's test took |
| `diff` | Unified diff from `baseRef` to `programRef` |

`baseRef` → `programRef` chains through the whole list, so you can render any
step's before/after without recomputation.

> **`tokensBefore == tokensAfter` on an accepted step is normal and correct.**
> In the example, 5 of 12 steps are token-neutral: `token_canonicalizer` renaming
> identifiers (`keep_me` → `b`) makes the program *simpler* without making it
> shorter. Determining acceptance from a token decrease would have discarded 42%
> of this run's trajectory, which is why the trace records acceptance directly
> from the reducer rather than inferring it.

---

## `candidates[]` — the full audit trail

Every variant tried, in the order Perses evaluated them.

```json
{
  "index": 0, "seq": 0,
  "workDirId": "000001",
  "eventKind": "TEST_EXECUTION",
  "status": "REJECTED",
  "becameBest": false,
  "exitCode": 1,
  "reducer": "node_priority-dfs", "reducerPass": 1,
  "editId": 0,
  "transformation": { ... }, "targets": [ ... ],
  "tokensBefore": 75, "tokensAfter": 1,
  "programRef": null, "baseRef": "p_7dec01b4",
  "patch": "--- best\n+++ candidate\n...",
  "metrics": { ... }, "provenance": { ... }
}
```

| Field | Meaning |
|---|---|
| `index` | Position in this array |
| `seq` | Order Perses emitted it. Dense and monotonic — a gap means a truncated file. |
| `workDirId` | The temporary directory this candidate was tested in, e.g. `"000001"`. The join key; also your test script's working directory. |
| `eventKind` | How this record arose — see below |
| `status` | The outcome — see below |
| `becameBest` | `true` if this candidate was applied. **Authoritative.** |
| `exitCode` | Your test's exit code. `0` = interesting. `99` = Perses rejected it as unparsable. |
| `editId` | Unique id of the tree edit. Stable across the file. |
| `tokensBefore` | Size of the best program when this candidate was built |
| `tokensAfter` | Size of this candidate |
| `programRef` | Pool key, or `null` if only a `patch` is stored |
| `baseRef` | The best program this candidate was derived from |
| `patch` | Unified diff from `baseRef` to this candidate |

Note `"tokensAfter": 1` above — that candidate deleted almost the entire program.
It failed, which is why the reducer then tried smaller deletions.

### `eventKind` — where the record came from

| Value | Meaning |
|---|---|
| `TEST_EXECUTION` | Your test script actually ran |
| `CACHE_HIT` | Perses recognised this variant and skipped the test |
| `CANCELLED` | Abandoned because a better candidate won the race |

### `status` — the outcome

| Value | Meaning |
|---|---|
| `INTERESTING` | The test passed. Usually, but not always, applied — see `becameBest`. |
| `REJECTED` | The test failed. The variant broke the property. |
| `INVALID` | Rejected by Perses as syntactically invalid (`exitCode: 99`) |
| `CACHE_HIT` | Not tested; verdict already known |
| `CANCELLED` | Not evaluated |

> **`INTERESTING` does not imply `becameBest`.** Perses tests several candidates
> concurrently and applies the first winner, cancelling the rest. A candidate can
> pass and still not be applied. **Always use `becameBest`** to count accepted steps.

> **`INVALID` cannot be seen by your test script.** Perses assigns exit code 99
> *after* your script returns, when it finds the result unparsable. Your test
> reported a plain failure; the distinction exists only in the merged trace.

### `transformation` — what kind of edit

| Field | Meaning |
|---|---|
| `editClass` | Perses' internal edit class, e.g. `NodeDeletionTreeEdit` |
| `actionsDescription` | Verbatim internal description |
| `type` | Normalised category |

| `type` | What it does |
|---|---|
| `DELETE` | Removes syntax-tree nodes |
| `DELTA_DEBUG` | Delta-debugging: bisects a list of nodes to find a removable subset |
| `LIST_MINIMISE` | Shrinks a repeated construct — statements, parameters, array elements |
| `TOKEN_SLICE` | Deletes a sliding window of raw tokens |
| `LINE_SLICE` | Deletes whole source lines |
| `HOIST` | Replaces a node with one of its descendants (`f(g(x))` → `g(x)`) |
| `REPLACE` | Substitutes one node for another, e.g. identifier canonicalisation |
| `LATRA` | A language-specific rewrite rule |
| `LLM` | A language-model-proposed transformation |
| `UNKNOWN` | Unrecognised — report it |

`actionsDescription` is raw internal text and its format is **not stable across
Perses versions**. Read `type` in code; show `actionsDescription` for debugging.
A real one:

```
DFS in org.perses.reduction.reducer.PersesNodeReducer[kleene_plus:kleene_plus__blockItemList_3]dd@2
```

That reads: depth-first node reducer, working on a `blockItemList` repetition,
delta-debugging with granularity 2.

### `targets[]` — which syntax nodes were changed

```json
{ "nodeId": 7, "ruleName": "functionDefinition",
  "ruleType": "OTHER_RULE", "replacingNodeId": null }
```

| Field | Meaning |
|---|---|
| `nodeId` | Stable identifier for the tree node |
| `ruleName` | Grammar rule, e.g. `functionDefinition`, `blockItem`. `null` for raw tokens. |
| `ruleType` | Structural category — see below |
| `replacingNodeId` | For replacements, the node substituted in. `null` for deletions. |

`ruleName` is the most human-readable field in the file: it tells you *what kind
of construct* was removed. Above, three `functionDefinition` nodes — Perses tried
deleting three whole functions at once.

#### `ruleType` values

These come from the grammar and explain **why** a reducer thought a node was safe
to delete.

| Value | Grammar meaning | Reduction significance |
|---|---|---|
| `KLEENE_STAR` | `x*` — zero or more | Every child is optional; all are deletable |
| `KLEENE_PLUS` | `x+` — one or more | All but one child deletable |
| `OPTIONAL` | `x?` — zero or one | The child can be removed entirely |
| `ALT_BLOCKS` | An alternation block | One branch may be substitutable for another |
| `OTHER_RULE` | Any ordinary rule | No structural guarantee; deletion is speculative |
| `null` | Not a parser rule | A raw token or placeholder — no rule type exists |

Kleene nodes are where reduction pays off: a `KLEENE_PLUS` over statements means
the reducer *knows* it may drop statements. `OTHER_RULE` deletions are guesses,
and fail more often.

`ruleType: null` is normal, not missing data — token slicers operate exclusively
on raw lexer tokens, which carry no rule type.

### `metrics`

| Field | Meaning |
|---|---|
| `testDurationMs` | How long your test took, measured by the wrapper |
| `engineReportedMs` | The same, measured by Perses. Small differences are normal; `-1` means unparsable. |
| `wrapperOverheadMs` | Tracing cost for this candidate — typically single-digit ms |
| `cumulativeTests` | Tests run up to and including this one |
| `chars` / `lines` | Size of this candidate's text |

### `provenance` — how much of this record is trustworthy

| Field | Meaning |
|---|---|
| `workDirId` | Repeated for convenience |
| `matched` | `true` if both halves of the trace were joined |
| `source` | `BOTH` (Perses + wrapper) or `LISTENER_ONLY` (Perses only) |
| `note` | Why a record is unmatched, when it is |

`LISTENER_ONLY` is expected for `CACHE_HIT` records — no script ran, so there is
nothing to join to, and `metrics` timings are `null`.

`LISTENER_ONLY` on a `TEST_EXECUTION` record is worth investigating: it usually
means a globally cached verdict was reused. Cross-check `globalCacheHits`.

---

## Recipes

**Count real accepted steps**

```python
sum(1 for c in d["candidates"] if c["becameBest"])   # not status == "INTERESTING"
```

**Reconstruct a rejected candidate's source**

```python
base = d["programs"][c["baseRef"]]["text"]   # then apply c["patch"]
```

**Reduction progress over time**

```python
[(s["testsRunSoFar"], s["tokensAfter"]) for s in d["steps"]]
```

**Which constructs get removed most**

```python
from collections import Counter
Counter(t["ruleName"] for s in d["steps"] for t in s["targets"])
```

**Wasted effort per accepted step**

```python
d["summary"]["totalCandidates"] / d["summary"]["acceptedSteps"]   # 111/12 ≈ 9.3
```

---

## Five things that surprise people

1. **`reductionRatio` is the fraction removed**, not remaining. `0.773` = 77% deleted.
2. **`tokensBefore == tokensAfter` on an accepted step is valid** — canonicalisation simplifies without shrinking.
3. **`INTERESTING` ≠ accepted.** Use `becameBest`.
4. **`testTimeMs` may exceed `wallTimeMs`** because tests run in parallel.
5. **`unmatchedWrapperLines: 1` is correct** — it is Perses' pre-run sanity check.
