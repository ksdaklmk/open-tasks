#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/android.yml"
grep -q 'api-level: 36' "$workflow"
grep -q 'api-level: "37.0"' "$workflow"
grep -q 'profile: pixel_6' "$workflow"
grep -q 'profile: pixel_tablet' "$workflow"
grep -q 'channel: canary' "$workflow"
! grep -Eq 'uses: [^#[:space:]]+@(v[0-9]+|main|master)([[:space:]]|$)' "$workflow"

# The connected gate runs every module that owns an androidTest source
# set — currently seven. Keep this list in sync when a module gains
# instrumented tests.
connected_modules=(
  :core:data:connectedDebugAndroidTest
  :app:connectedDebugAndroidTest
  :feature:tasks:connectedDebugAndroidTest
  :feature:projects:connectedDebugAndroidTest
  :feature:schedule:connectedDebugAndroidTest
  :feature:more:connectedDebugAndroidTest
  :feature:home:connectedDebugAndroidTest
)
for module in "${connected_modules[@]}"; do
  grep -q "$module" "$workflow"
done
