#!/usr/bin/env bash

set -u

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
hook="$repo_root/.claude/hooks/reject-raw-colour.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/open-tasks-colour-hook.XXXXXX")"
failures=0

cleanup() {
  rm -rf "$test_root"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

expect_blocked() {
  local name="$1"
  local payload="$2"
  local target="$3"
  local project_dir="${4:-$repo_root}"
  local stdout_file="$test_root/$name.stdout"
  local stderr_file="$test_root/$name.stderr"
  local status

  if printf '%s\n' "$payload" |
    CLAUDE_PROJECT_DIR="$project_dir" "$hook" >"$stdout_file" 2>"$stderr_file"; then
    status=0
  else
    status=$?
  fi

  if [[ "$status" -ne 2 ]]; then
    fail "$name exited $status instead of 2"
  fi
  if ! grep -q 'Raw hex colour' "$stderr_file"; then
    fail "$name did not emit the actionable raw-colour error"
  fi
  if ! grep -q 'core/designsystem' "$stderr_file"; then
    fail "$name did not identify the design-system token location"
  fi
  if [[ -e "$target" ]]; then
    fail "$name wrote $target"
  fi
}

expect_allowed() {
  local name="$1"
  local payload="$2"
  local stdout_file="$test_root/$name.stdout"
  local stderr_file="$test_root/$name.stderr"
  local status

  if printf '%s\n' "$payload" | "$hook" >"$stdout_file" 2>"$stderr_file"; then
    status=0
  else
    status=$?
  fi

  if [[ "$status" -ne 0 ]]; then
    fail "$name exited $status instead of 0"
  fi
  if [[ -s "$stdout_file" || -s "$stderr_file" ]]; then
    fail "$name emitted unexpected output"
  fi
}

raw_write_target="$test_root/RawWrite.kt"
raw_edit_target="$test_root/RawEdit.kt"
traversal_target="$repo_root/core/designsystem/../../feature/escape/Raw.kt"
symlink_project="$test_root/symlink-project"
symlink_escape="$symlink_project/feature/escape"
symlink_target="$symlink_project/core/designsystem/link/Raw.kt"

mkdir -p "$symlink_project/core/designsystem" "$symlink_escape"
ln -s "$symlink_escape" "$symlink_project/core/designsystem/link"

expect_blocked \
  "raw-write" \
  "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$raw_write_target\",\"content\":\"package hook.harness\\n\\nval raw = Color(0xFF112233)\"}}" \
  "$raw_write_target"

expect_blocked \
  "raw-edit" \
  "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$raw_edit_target\",\"old_string\":\"val colour = Color.Blue\",\"new_string\":\"val colour = Color(0xFF112233)\"}}" \
  "$raw_edit_target"

expect_blocked \
  "design-system-traversal" \
  "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$traversal_target\",\"content\":\"val escaped = Color(0xFF112233)\"}}" \
  "$traversal_target"

expect_blocked \
  "design-system-symlink-escape" \
  "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$symlink_target\",\"content\":\"val escaped = Color(0xFF112233)\"}}" \
  "$symlink_target" \
  "$symlink_project"

expect_allowed \
  "clean-kotlin" \
  "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$test_root/Clean.kt\",\"content\":\"package hook.harness\\n\\nval colour = Color.Blue\"}}"

expect_allowed \
  "non-kotlin" \
  "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$test_root/palette.md\",\"content\":\"Example only: Color(0xFF112233)\"}}"

expect_allowed \
  "design-system-token" \
  "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$repo_root/core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/PaletteHookSample.kt\",\"content\":\"val token = Color(0xFF112233)\"}}"

if [[ "$failures" -ne 0 ]]; then
  printf '%s colour-hook protocol check(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'Colour-hook protocol checks passed: 4 blocked, 3 allowed\n'
