#!/usr/bin/env bash
# Compatibility entry point. 062 is the single canonical deployment SQL.
# Verify its final catalog against the archived history, plus data preservation.
set -eu
cd "$(dirname "$0")/.."
node supabase/tests/run-consolidated-baseline-test.mjs
