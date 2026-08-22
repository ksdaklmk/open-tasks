#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/android.yml"
security_workflow=".github/workflows/security.yml"
wrapper_properties="gradle/wrapper/gradle-wrapper.properties"

checksum_count="$(grep -Ec '^[[:space:]]*distributionSha256Sum[[:space:]]*=' "$wrapper_properties" || true)"
test "$checksum_count" -eq 1
grep -Eq '^distributionSha256Sum=[0-9A-Fa-f]{64}$' "$wrapper_properties"
grep -q '^distributionPath=wrapper/dists-sha256-v1$' "$wrapper_properties"
grep -q '^zipStorePath=wrapper/dists-sha256-v1$' "$wrapper_properties"

grep -q 'api-level: 36' "$workflow"
grep -q 'api-level: "37.0"' "$workflow"
grep -q 'profile: pixel_6' "$workflow"
grep -q 'profile: pixel_tablet' "$workflow"
grep -q 'channel: canary' "$workflow"
! grep -Eq 'uses: [^#[:space:]]+@(v[0-9]+|main|master)([[:space:]]|$)' "$workflow"

test -f "$security_workflow"
! grep -Eq 'uses: [^#[:space:]]+@(v[0-9]+|main|master)([[:space:]]|$)' "$security_workflow"
uses_count="$(grep -Ec '^[[:space:]]*- uses:' "$security_workflow")"
pinned_count="$(grep -Ec '^[[:space:]]*- uses: [^@[:space:]]+@[0-9a-f]{40}([[:space:]]+#.*)?$' "$security_workflow")"
test "$uses_count" -eq "$pinned_count"
grep -q 'github/codeql-action/init@4c0873ef8656cb3c50b3f42fb63bc1ade0cfa827' "$security_workflow"
grep -q 'github/codeql-action/analyze@4c0873ef8656cb3c50b3f42fb63bc1ade0cfa827' "$security_workflow"
grep -q 'actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294' "$security_workflow"
grep -q 'languages: java-kotlin' "$security_workflow"
grep -q 'build-mode: manual' "$security_workflow"
grep -q './gradlew clean :app:assembleDebug' "$security_workflow"
grep -q 'fail-on-severity: high' "$security_workflow"
grep -q "if: github.event_name == 'pull_request'" "$security_workflow"
grep -q 'security-events: write' "$security_workflow"
grep -q 'permissions: {}' "$security_workflow"
! grep -Eq 'permissions: write-all|(^|[^[:alnum:]_])(secrets\.|github\.token|ACTIONS_RUNTIME_TOKEN)' "$security_workflow"
test "$(grep -Ec '^[[:space:]]{6}contents: read$' "$security_workflow")" -eq 2
test "$(grep -Ec '^[[:space:]]{6}security-events: write$' "$security_workflow")" -eq 1
! grep -Eiq 'run:.*(echo|print).*(token|secret|password|credential)' "$security_workflow"

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
