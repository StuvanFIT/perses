#!/usr/bin/env bash
#
# Perses trace wrapper.
#
#   export PERSES_REAL_TEST=/abs/path/to/your/r.sh
#   export PERSES_TRACE_DIR=/abs/path/to/trace_out
#   perses --input-file t.c \
#          --test-script /abs/path/to/perses-trace-wrapper.sh \
#          --trace-ndjson-file "$PERSES_TRACE_DIR/engine.ndjson"
#   perses-trace-merge.py --engine ... --wrapper ... --staging ... --out reduction.json
#
# Environment:
#   PERSES_REAL_TEST   (required) absolute path to your interestingness test.
#   PERSES_TRACE_DIR   (required) absolute path to an existing output directory.
#   PERSES_TRACE_FILES (optional) space-separated relative paths to stage.
#                      Default: the whole candidate directory. Narrow this if your
#                      input has large immutable dependency files.
#
# ---------------------------------------------------------------------------
# Four constraints. Do not "simplify" these away.
#
#   1. EXIT CODE PASSTHROUGH. Perses treats any non-zero code as "not
#      interesting"; altering it changes the reduction itself.
#
#   2. NEVER WRITE INTO $PWD. Perses deletes every unexpected file in the
#      candidate directory right after the script returns, and a file that
#      lingers can abort the whole reduction.
#
#   3. CONCURRENCY. Perses runs many copies of this script at once. The log
#      append is serialised with flock.
#
#   4. DO NOT CONFOUND THE OVERHEAD MEASUREMENT. The hot path is exactly:
#         timestamp -> stage raw copy -> timestamp -> delegate -> timestamp
#      Hashing, compression, diffing and line/char counting are NOT done here;
#      they run offline in perses-trace-merge.py. The test-duration window
#      brackets ONLY the delegation, so staging cost lands in the separately
#      reported overhead figure instead of inflating the measured test time.
#      `EPOCHREALTIME` is used in preference to `date` because it is a bash
#      builtin and costs no fork.
# ---------------------------------------------------------------------------

set -u
set -o pipefail

readonly REAL_TEST="${PERSES_REAL_TEST:?PERSES_REAL_TEST must be set}"
readonly TRACE_DIR="${PERSES_TRACE_DIR:?PERSES_TRACE_DIR must be set}"

now_ms() {
  if [[ -n "${EPOCHREALTIME:-}" ]]; then
    local t="${EPOCHREALTIME/,/.}"
    local sec="${t%.*}"
    local frac="${t#*.}"
    printf '%s' "$(( sec * 1000 + 10#${frac:0:3} ))"
  else
    date +%s%3N
  fi
}

readonly WRAPPER_START_MS="$(now_ms)"

# $PWD is the per-candidate directory Perses created for this invocation. Its
# basename is the join key the engine reports as workDirId.
readonly WORK_DIR_ID="${PWD##*/}"
readonly STAGE_DIR="${TRACE_DIR}/staging/${WORK_DIR_ID}"

# Raw copy only -- no hashing, no compression. Constraint 4.
mkdir -p "${STAGE_DIR}" 2>/dev/null
if [[ -n "${PERSES_TRACE_FILES:-}" ]]; then
  # shellcheck disable=SC2086
  cp -p --parents ${PERSES_TRACE_FILES} "${STAGE_DIR}/" 2>/dev/null
else
  cp -a . "${STAGE_DIR}/" 2>/dev/null
fi

readonly TEST_START_MS="$(now_ms)"

# ---- delegate to the user's real interestingness test, unchanged ----------
"${REAL_TEST}"
readonly EXIT_CODE=$?
# ---------------------------------------------------------------------------

readonly TEST_END_MS="$(now_ms)"
readonly TEST_MS=$(( TEST_END_MS - TEST_START_MS ))

record="{\
\"workDirId\":\"${WORK_DIR_ID}\",\
\"exitCode\":${EXIT_CODE},\
\"status\":\"$( [[ ${EXIT_CODE} -eq 0 ]] && echo pass || echo fail )\",\
\"startMillis\":${TEST_START_MS},\
\"testDurationMs\":${TEST_MS},"

# Records stay well under PIPE_BUF now that no program text is embedded, but
# flock still guarantees it under concurrency. Constraint 3.
exec 9>>"${TRACE_DIR}/.wrapper.lock"
if flock -w 30 9; then
  # The overhead figure is closed out as late as possible so that it accounts
  # for staging, timestamping and the log append itself.
  printf '%s\"wrapperOverheadMs\":%s}\n' \
    "${record}" "$(( $(now_ms) - WRAPPER_START_MS - TEST_MS ))" \
    >> "${TRACE_DIR}/wrapper.ndjson"
  flock -u 9
fi
exec 9>&-

# Constraint 1.
exit "${EXIT_CODE}"
