#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
schema_dir="$repo_root/core/data/schemas/app.opentasks.core.data.db.VaultDatabase"

backup_dir="$(mktemp -d "${TMPDIR:-/tmp}/check-schema-drift.XXXXXX")"
trap 'rm -rf "$backup_dir"' EXIT

cp "$schema_dir"/*.json "$backup_dir"/

# Room only ever (re)generates the schema file for the current `@Database(version = N)`
# value, and it refuses to overwrite a schema file that already exists on disk. To prove
# the checked-in schema for the current version still matches the live entities, remove
# only that file so Room is forced to regenerate it; every older, frozen version is
# verified below by a plain byte-for-byte comparison, since Room never touches them.
latest_version="$(ls "$schema_dir" | sed -n -E 's/^([0-9]+)\.json$/\1/p' | sort -n | tail -1)"
if [[ -z "$latest_version" ]]; then
    echo "No versioned schema files found in $schema_dir" >&2
    exit 1
fi
latest_file="$schema_dir/$latest_version.json"
rm -f "$latest_file"

# A Gradle (tool) failure here is not a drift verdict: no comparison has happened yet.
# Restore the deleted top-version file from the backup so the working tree is left
# exactly as it was found, and so a later run doesn't mistake the next-lower version
# for "current" and delete a frozen historical schema instead.
if ! (cd "$repo_root" && ./gradlew :core:data:kspDebugKotlin --rerun-tasks); then
    echo "check-schema-drift: Gradle failed regenerating $latest_version.json;" \
        "restoring it from backup (not a drift result)." >&2
    cp "$backup_dir/$latest_version.json" "$latest_file"
    exit 1
fi

status=0

for expected in "$backup_dir"/*.json; do
    name="$(basename "$expected")"
    actual="$schema_dir/$name"
    if [[ ! -f "$actual" ]]; then
        echo "Schema drift: $name is missing from $schema_dir after regeneration" >&2
        status=1
        continue
    fi
    if ! cmp -s "$expected" "$actual"; then
        echo "Schema drift: $name changed after regeneration" >&2
        status=1
    fi
done

for actual in "$schema_dir"/*.json; do
    name="$(basename "$actual")"
    if [[ ! -f "$backup_dir/$name" ]]; then
        echo "Schema drift: $name was added by regeneration" >&2
        status=1
    fi
done

if [[ "$status" -eq 0 ]]; then
    echo "No schema drift: $schema_dir matches the current Room entities."
fi

exit "$status"
