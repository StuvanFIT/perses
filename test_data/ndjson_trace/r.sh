#!/usr/bin/env bash
#
# Hermetic interestingness test for the NDJSON trace integration test.
#
# Deliberately uses no compiler. The repo's other C benchmarks invoke gcc AND
# clang, which makes them unrunnable on machines without both, and this test is
# about the shape of the emitted trace rather than about compiling anything.
grep -q "keep_me" t.c || exit 1
grep -q "42" t.c || exit 1
exit 0
