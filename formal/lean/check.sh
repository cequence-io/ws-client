#!/usr/bin/env bash
# Checks the Lean proofs: builds every module and the conformance executable, fails on any
# `sorry`, and fails when a headline theorem (Axioms.lean) rests on an axiom beyond Lean's
# standard three. The toolchain comes from lean-toolchain (elan installs it on first use).
set -euo pipefail
cd "$(dirname "$0")"

if ! build=$(lake build 2>&1); then
  echo "$build"
  exit 1
fi
if grep -q "declaration uses 'sorry'" <<<"$build"; then
  grep -B1 -A1 "declaration uses 'sorry'" <<<"$build"
  echo "FAIL: a proof uses sorry"
  exit 1
fi

report=$(lake env lean Axioms.lean)
theorems=$(grep -c "depends on axioms\|does not depend on any axioms" <<<"$report" || true)
used=$(tr '\n' ' ' <<<"$report" | grep -o 'axioms: \[[^]]*\]' | sed 's/axioms: \[//; s/\]//' |
  tr ',' '\n' | tr -d ' ' | sort -u | grep -v '^$' || true)
unexpected=$(grep -v -x -e propext -e Classical.choice -e Quot.sound <<<"$used" || true)
if [ -n "$unexpected" ]; then
  echo "$report"
  echo "FAIL: unexpected axioms: $unexpected"
  exit 1
fi
echo "OK: $theorems theorems checked, axioms used: $(tr '\n' ' ' <<<"$used")"
