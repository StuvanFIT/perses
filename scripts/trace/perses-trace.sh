#!/usr/bin/env bash
#
# One-shot entry point: run Perses with tracing enabled and produce reduction.json.
#
#   perses-trace.sh --input t.c --test my_test.sh --out ./trace_out [-- <extra perses args>]
#
# This exists so the three pieces are always wired together correctly. Two of the
# constraints it enforces are easy to get wrong by hand and fail confusingly:
#
#   1. Perses requires the test script to sit in the SAME DIRECTORY as the input
#      file (a hard `require` in RegularReductionInputs). The wrapper is therefore
#      copied next to a private copy of the input, and the user's real test is kept
#      outside that directory and referenced by absolute path.
#
#   2. The trace directory MUST be fresh. workDirId -- the join key -- is only
#      unique within a single run, because Perses restarts its candidate directory
#      sequence at 000000 each time while the wrapper log and staging directory are
#      append-only. Reusing a directory silently joins records to the wrong
#      candidates, so this script refuses to reuse a non-empty one.

set -o nounset
set -o pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
readonly WRAPPER="${SCRIPT_DIR}/perses-trace-wrapper.sh"
readonly MERGER="${SCRIPT_DIR}/perses-trace-merge.py"

INPUT=""; TEST=""; OUT=""; JAR="${PERSES_JAR:-}"; FORCE=0
EXTRA=()

usage() {
  cat >&2 <<USAGE
Usage: $0 --input <file> --test <script> --out <dir> [options] [-- <perses args>]

  --input   FILE   program to reduce
  --test    FILE   your interestingness test (exit 0 == interesting)
  --out     DIR    trace output directory (must not already contain a run)
  --jar     FILE   perses_deploy.jar (default: \$PERSES_JAR)
  --force          delete the output directory if it already exists
USAGE
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT="$2"; shift 2 ;;
    --test)  TEST="$2";  shift 2 ;;
    --out)   OUT="$2";   shift 2 ;;
    --jar)   JAR="$2";   shift 2 ;;
    --force) FORCE=1;    shift   ;;
    --)      shift; EXTRA=("$@"); break ;;
    *)       usage ;;
  esac
done

[[ -n "${INPUT}" && -n "${TEST}" && -n "${OUT}" ]] || usage
[[ -f "${INPUT}" ]] || { echo "error: no such input file: ${INPUT}" >&2; exit 1; }
[[ -x "${TEST}"  ]] || { echo "error: test script missing or not executable: ${TEST}" >&2; exit 1; }
[[ -n "${JAR}" && -f "${JAR}" ]] || {
  echo "error: perses_deploy.jar not found. Pass --jar or set PERSES_JAR." >&2; exit 1; }

# Constraint 2: never merge two runs into one trace directory.
if [[ -e "${OUT}" ]]; then
  if [[ "${FORCE}" -eq 1 ]]; then
    rm -rf "${OUT}"
  elif [[ -n "$(ls -A "${OUT}" 2>/dev/null)" ]]; then
    echo "error: ${OUT} already exists and is not empty." >&2
    echo "       The join key is only unique within one run, so a trace directory" >&2
    echo "       cannot be reused. Pass --force to delete it, or choose a new path." >&2
    exit 1
  fi
fi

mkdir -p "${OUT}/work" || exit 1
readonly OUT_ABS="$(cd -- "${OUT}" && pwd)"
readonly TEST_ABS="$(cd -- "$(dirname -- "${TEST}")" && pwd)/$(basename -- "${TEST}")"
readonly INPUT_BASE="$(basename -- "${INPUT}")"

# Constraint 1: wrapper and input side by side; the real test stays outside.
cp "${INPUT}"  "${OUT_ABS}/work/${INPUT_BASE}"
cp "${INPUT}"  "${OUT_ABS}/original.${INPUT_BASE##*.}"
cp "${WRAPPER}" "${OUT_ABS}/work/"
chmod +x "${OUT_ABS}/work/$(basename -- "${WRAPPER}")"

export PERSES_REAL_TEST="${TEST_ABS}"
export PERSES_TRACE_DIR="${OUT_ABS}"

echo "==> reducing ${INPUT_BASE} (trace -> ${OUT_ABS})"
(
  cd "${OUT_ABS}/work" || exit 1
  java -jar "${JAR}" \
    --input-file "${INPUT_BASE}" \
    --test-script "$(basename -- "${WRAPPER}")" \
    --trace-ndjson-file "${OUT_ABS}/engine.ndjson" \
    --output-dir "${OUT_ABS}/result" \
    "${EXTRA[@]+"${EXTRA[@]}"}"
)
readonly PERSES_STATUS=$?

if [[ "${PERSES_STATUS}" -ne 0 ]]; then
  echo "warning: perses exited with ${PERSES_STATUS}; merging whatever was traced" >&2
fi

echo "==> merging"
"${MERGER}" \
  --engine        "${OUT_ABS}/engine.ndjson" \
  --wrapper       "${OUT_ABS}/wrapper.ndjson" \
  --staging       "${OUT_ABS}/staging" \
  --original-file "${OUT_ABS}/original.${INPUT_BASE##*.}" \
  --source-file   "${INPUT_BASE}" \
  --language      "${INPUT_BASE##*.}" \
  --test-script   "$(basename -- "${TEST}")" \
  --out           "${OUT_ABS}/reduction.json" || exit 1

echo "==> ${OUT_ABS}/reduction.json"
