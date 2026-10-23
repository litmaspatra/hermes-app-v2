#!/bin/bash
# Compile and run the pure-Java unit tests (no Android jar needed): the read-only approval policy and the
# voice-command grammar. Used by CI; run it locally with: bash tools/java-unit-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
P=android/src/com/omarqaterge/hermesmobile
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -d "$OUT" "$P/ApprovalPolicy.java" "$P/VoiceCommands.java" "$P/ContactMatcher.java" "$P/ProsodyPlanner.java" "$P/ApprovalTracker.java" "$P/ApprovalInfo.java" android/test/*.java
for t in ApprovalPolicyTest VoiceCommandsTest ContactMatcherTest ProsodyPlannerTest ApprovalTrackerTest ApprovalInfoTest; do
  java -cp "$OUT" "com.omarqaterge.hermesmobile.$t"
done
