#!/usr/bin/env bash

set -u

payload="$(cat)"
file_path="$(jq -r '.tool_input.file_path // empty' <<<"$payload")"
proposed_content="$(
  jq -r '.tool_input.content // .tool_input.new_string // empty' <<<"$payload"
)"

case "$file_path" in
  core/designsystem/* | */core/designsystem/*)
    exit 0
    ;;
  *.kt)
    if grep -Fq 'Color(0x' <<<"$proposed_content"; then
      printf \
        'Raw hex colour in proposed write to %s. Define colours as oklch() tokens in core/designsystem (DESIGN.md) and reference the token here.\n' \
        "$file_path" >&2
      exit 2
    fi
    ;;
esac

exit 0
