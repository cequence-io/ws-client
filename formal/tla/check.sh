#!/bin/bash
# Model-checks the TLA+ specs of the stream layer with TLC and compares every outcome with the
# expected one: the specs must hold, the mutants (one seeded bug each) must be rejected.
#
#   check.sh [--fetch] [--list] [--slow] [name...]   names as printed by --list; default: all
#                                                    but the slow ones (--slow adds them; a name
#                                                    given explicitly always runs)
#
# The TLA+ tools jar is not part of the repository: point TLA2TOOLS_JAR at one, or pass --fetch
# to download the pinned release into target/ (checked against its SHA-256).
#
# Environment:
#   TLA2TOOLS_JAR   the tools jar                      (default target/tla2tools.jar)
#   TLC_WORKERS     TLC worker threads                 (default auto)
#   TLC_MAX_MB      TLC keeps its state queue on disk: a run is stopped when its state files
#                   grow beyond this many MB           (default 700)
#
# Everything is written below target/ (git-ignored): the specs are copied there, a mutant's
# patch is applied, the PlusCal algorithm is translated, TLC runs.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
work="$here/target"
jar="${TLA2TOOLS_JAR:-$work/tla2tools.jar}"
workers="${TLC_WORKERS:-auto}"
limit_mb="${TLC_MAX_MB:-700}"

jar_version="v1.7.4"
jar_url="https://github.com/tlaplus/tlaplus/releases/download/$jar_version/tla2tools.jar"
jar_sha256="936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"

# name | module | config | mutant patch (- for none) | expected outcome | slow (optional)
#
# Outcomes: ok (no error), deadlock, invariant, property (a temporal property), assert.
# A check marked `slow` (a minute or more on 20 cores, over a million states) runs only with
# --slow or by name.
checks="
ZioCursor            ZioCursor      ZioCursor            -               ok
CursorDrain          CursorDrain    CursorDrain          -               ok
CursorDrainWide      CursorDrain    CursorDrainWide      -               ok
TransformChain       TransformChain TransformChain       -               ok
TransformChainEarly  TransformChain TransformChainEarly  -               ok
TransformChainBurst  TransformChain TransformChainBurst  -               ok
TransformChainOuts   TransformChain TransformChainOuts   -               ok
TransformChainLegacy TransformChain TransformChainLegacy -               deadlock
ZioCursorM1          ZioCursor      ZioCursor            ZioCursorM1     deadlock
ZioCursorM2          ZioCursor      ZioCursor            ZioCursorM2     deadlock
CursorDrainM1        CursorDrain    CursorDrain          CursorDrainM1   invariant
CursorDrainM2        CursorDrain    CursorDrain          CursorDrainM2   invariant
CursorDrainM3        CursorDrain    CursorDrain          CursorDrainM3   assert
CursorDrainM4        CursorDrain    CursorDrain          CursorDrainM4   invariant
TransformChainM1     TransformChain TransformChainBurst  TransformChainM1 deadlock
TransformChainM2     TransformChain TransformChain       TransformChainM2 invariant
TransformChainM3     TransformChain TransformChain       TransformChainM3 invariant
PreflightHandover    PreflightHandover PreflightHandover  -               ok
PreflightWindow      PreflightHandover PreflightHandoverWindow -          invariant
PreflightHandoverM1  PreflightHandover PreflightHandover  PreflightHandoverM1 invariant
PreflightHandoverM2  PreflightHandover PreflightHandover  PreflightHandoverM2 invariant
SubscriberCursor       SubscriberCursor SubscriberCursor       -                    ok
SubscriberCursorError  SubscriberCursor SubscriberCursorError  -                    ok
SubscriberCursorCancel SubscriberCursor SubscriberCursorCancel -                    ok
SubscriberCursorWide   SubscriberCursor SubscriberCursorWide   -                    ok
SubscriberCursorAsync  SubscriberCursor SubscriberCursorAsync  -                    ok
SubscriberCursorM1     SubscriberCursor SubscriberCursor       SubscriberCursorM1   invariant
SubscriberCursorM2     SubscriberCursor SubscriberCursor       SubscriberCursorM2   deadlock
SubscriberCursorM3     SubscriberCursor SubscriberCursorCancel SubscriberCursorM3   invariant
SubscriberCursorM4     SubscriberCursor SubscriberCursor       SubscriberCursorM4   deadlock
SubscriberCursorM5     SubscriberCursor SubscriberCursor       SubscriberCursorM5   deadlock
SubscriberCursorM6     SubscriberCursor SubscriberCursorCancel SubscriberCursorM6   invariant
SubscriberCursorM7     SubscriberCursor SubscriberCursorCancel SubscriberCursorM7   invariant
CallTimeout            CallTimeout      CallTimeout            -                    ok
CallTimeoutNoLate      CallTimeout      CallTimeoutNoLate      -                    ok
CallTimeoutNoCancel    CallTimeout      CallTimeoutNoCancel    -                    ok
CallTimeoutM1          CallTimeout      CallTimeout            CallTimeoutM1        invariant
CallTimeoutM2          CallTimeout      CallTimeout            CallTimeoutM2        invariant
CallTimeoutM3          CallTimeout      CallTimeout            CallTimeoutM3        invariant
CallTimeoutM4          CallTimeout      CallTimeout            CallTimeoutM4        invariant
CallTimeoutM5          CallTimeout      CallTimeout            CallTimeoutM5        invariant
CallTraverse           CallTraverse     CallTraverse           -                    ok
CallTraverseNoCancel   CallTraverse     CallTraverseNoCancel   -                    ok
CallTraverseWide       CallTraverse     CallTraverseWide       -                    ok
CallTraverseSerial     CallTraverse     CallTraverseSerial     -                    ok
CallTraverseM1         CallTraverse     CallTraverse           CallTraverseM1       invariant
CallTraverseM2         CallTraverse     CallTraverse           CallTraverseM2       invariant
CallTraverseM3         CallTraverse     CallTraverseNoCancel   CallTraverseM3       invariant
CallTraverseM4         CallTraverse     CallTraverse           CallTraverseM4       deadlock
CallTraverseM5         CallTraverse     CallTraverse           CallTraverseM5       invariant
BroadcastHub           BroadcastHub     BroadcastHub           -                    ok
BroadcastHubWide       BroadcastHub     BroadcastHubWide       -                    ok
BroadcastHubError      BroadcastHub     BroadcastHubError      -                    ok
BroadcastHubDrain      BroadcastHub     BroadcastHubDrain      -                    ok
BroadcastHubSync       BroadcastHub     BroadcastHubSync       -                    ok
BroadcastHubTwo        BroadcastHub     BroadcastHubTwo        -                    ok
BroadcastHubShutdown   BroadcastHub     BroadcastHubShutdown   -                    ok
BroadcastHubCancel     BroadcastHub     BroadcastHubCancel     -                    ok
BroadcastHubShutdown2  BroadcastHub     BroadcastHubShutdown2  -                    ok        slow
BroadcastHubCancel2    BroadcastHub     BroadcastHubCancel2    -                    ok        slow
BroadcastHubM1         BroadcastHub     BroadcastHub           BroadcastHubM1       invariant
BroadcastHubM2         BroadcastHub     BroadcastHubShutdown   BroadcastHubM2       invariant
BroadcastHubM3         BroadcastHub     BroadcastHubShutdown   BroadcastHubM3       invariant
BroadcastHubM4         BroadcastHub     BroadcastHubCancel     BroadcastHubM4       invariant
BroadcastHubM5         BroadcastHub     BroadcastHubWide       BroadcastHubM5       deadlock
BroadcastHubM6         BroadcastHub     BroadcastHubWide       BroadcastHubM6       deadlock
BroadcastHubM7         BroadcastHub     BroadcastHubWide       BroadcastHubM7       deadlock
"

fetch=false
all_slow=false
selected=()
for arg in "$@"; do
  case "$arg" in
    --fetch) fetch=true ;;
    --slow) all_slow=true ;;
    --list) echo "$checks" | awk 'NF { printf "%-22s expected: %-10s %s\n", $1, $5, $6 }'; exit 0 ;;
    -*) echo "unknown option $arg" >&2; exit 2 ;;
    *) selected+=("$arg") ;;
  esac
done

mkdir -p "$work"

if [ ! -f "$jar" ]; then
  if ! $fetch; then
    echo "no TLA+ tools jar at $jar: set TLA2TOOLS_JAR or pass --fetch ($jar_url)" >&2
    exit 2
  fi
  curl --fail --silent --show-error --location --output "$jar.part" "$jar_url" || exit 2
  if ! echo "$jar_sha256  $jar.part" | sha256sum --check --status; then
    echo "the downloaded jar does not have the pinned SHA-256" >&2
    rm -f "$jar.part"
    exit 2
  fi
  mv "$jar.part" "$jar"
fi

# TLC's verdict, from its output
outcome_of() {
  if grep -q "^STOPPED" "$1"; then echo "stopped"
  elif grep -q "Error: Deadlock reached" "$1"; then echo "deadlock"
  elif grep -q "Error: Invariant .* is violated" "$1"; then echo "invariant"
  elif grep -q "Error: Temporal properties were violated" "$1"; then echo "property"
  elif grep -q "The first argument of Assert evaluated to FALSE" "$1"; then echo "assert"
  elif grep -q "Model checking completed. No error has been found." "$1"; then echo "ok"
  else echo "error"
  fi
}

run_check() {
  local name="$1" module="$2" config="$3" mutant="$4"
  local dir="$work/$name" log="$work/$name.log"
  rm -rf "$dir"
  mkdir -p "$dir"
  cp "$here/$module.tla" "$here/$config.cfg" "$dir/"
  cd "$dir" || return 2
  if [ "$mutant" != "-" ]; then
    patch --quiet -p1 < "$here/mutants/$mutant.patch" || return 2
  fi
  java -cp "$jar" pcal.trans -nocfg "$module.tla" > "$log" 2>&1 || return 2

  java -XX:+UseParallelGC -cp "$jar" tlc2.TLC -workers "$workers" -metadir "$dir/states" \
    -config "$config.cfg" "$module.tla" >> "$log" 2>&1 &
  local tlc=$!
  while kill -0 $tlc 2>/dev/null; do
    sleep 1
    local used
    used=$(du -sm "$dir/states" 2>/dev/null | cut -f1)
    if [ "${used:-0}" -gt "$limit_mb" ]; then
      kill $tlc
      echo "STOPPED: the state files grew beyond $limit_mb MB" >> "$log"
    fi
  done
  wait $tlc 2>/dev/null
  cd "$here" || return 2
  rm -rf "$dir"
}

failed=0
ran=0
while read -r name module config mutant expected flag; do
  [ -z "$name" ] && continue
  if [ ${#selected[@]} -gt 0 ] && [[ ! " ${selected[*]} " == *" $name "* ]]; then continue; fi
  if [ ${#selected[@]} -eq 0 ] && [ "$flag" = "slow" ] && ! $all_slow; then continue; fi
  ran=$((ran + 1))
  start=$SECONDS
  run_check "$name" "$module" "$config" "$mutant"
  log="$work/$name.log"
  actual=$(outcome_of "$log")
  states=$(grep -o "[0-9]* distinct states found" "$log" | tail -1 | cut -d' ' -f1)
  violated=$(grep -o "Error: Invariant [A-Za-z0-9_]* is violated" "$log" | head -1 | cut -d' ' -f3)
  detail="${states:-?} distinct states, $((SECONDS - start)) s${violated:+, $violated}"
  if [ "$actual" = "$expected" ]; then
    printf "PASS  %-22s %-10s (%s)\n" "$name" "$actual" "$detail"
  else
    printf "FAIL  %-22s %-10s expected %s (%s) - see %s\n" "$name" "$actual" "$expected" "$detail" "$log"
    failed=$((failed + 1))
  fi
done <<< "$checks"

echo "$ran checks; failed: $failed"
# a selection that matches nothing is a typo, not a pass
[ "$failed" -eq 0 ] && [ "$ran" -gt 0 ]
