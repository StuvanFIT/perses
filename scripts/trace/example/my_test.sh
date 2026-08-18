#!/usr/bin/env bash
# Interestingness test: the program must still compile with gcc and print 42.
# Deliberately uses only gcc so it runs anywhere.
set -o pipefail
gcc -w -o a.out t.c 2>/dev/null || exit 1
out="$(./a.out 2>/dev/null)" || exit 1
[[ "${out}" == "42" ]] || exit 1
exit 0
