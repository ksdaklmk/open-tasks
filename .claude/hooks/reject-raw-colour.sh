#!/usr/bin/env bash

set -u

payload="$(cat)"
file_path="$(jq -r '.tool_input.file_path // empty' <<<"$payload")"
proposed_content="$(
  jq -r '.tool_input.content // .tool_input.new_string // empty' <<<"$payload"
)"

is_design_system_target() {
  local default_project_root
  local project_root
  local design_system_root
  local target_path="$file_path"
  local target_parent
  local canonical_parent

  default_project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
  project_root="$(
    cd "${CLAUDE_PROJECT_DIR:-$default_project_root}" 2>/dev/null && pwd -P
  )" || return 1
  design_system_root="$(
    cd "$project_root/core/designsystem" 2>/dev/null && pwd -P
  )" || return 1

  if [[ "$target_path" != /* ]]; then
    target_path="$project_root/$target_path"
  fi
  target_parent="$(dirname "$target_path")"
  canonical_parent="$(cd "$target_parent" 2>/dev/null && pwd -P)" || return 1

  case "$canonical_parent/" in
    "$design_system_root/"*)
      return 0
      ;;
  esac
  return 1
}

if [[ "$file_path" == *.kt ]] &&
  grep -Fq 'Color(0x' <<<"$proposed_content"; then
  if is_design_system_target; then
    exit 0
  fi
  printf \
    'Raw hex colour in proposed write to %s. Define colours as oklch() tokens in core/designsystem (DESIGN.md) and reference the token here.\n' \
    "$file_path" >&2
  exit 2
fi

exit 0
