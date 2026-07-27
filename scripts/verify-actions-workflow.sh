#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/android.yml"
grep -q 'api-level: 36' "$workflow"
grep -q 'api-level: 37' "$workflow"
grep -q 'profile: pixel_6' "$workflow"
grep -q 'profile: pixel_tablet' "$workflow"
grep -q 'channel: canary' "$workflow"
! grep -Eq 'uses: [^#[:space:]]+@(v[0-9]+|main|master)([[:space:]]|$)' "$workflow"
