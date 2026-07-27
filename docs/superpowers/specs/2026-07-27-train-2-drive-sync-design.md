# Train 2 — Drive Identity and Core Sync Design

## Goal

Implement P1-D01, P1-D03, and P1-D04: user-authorised Drive app-data access,
resumable encrypted transport, deterministic synchronisation, and visible sync
health without introducing a server.

## Authorisation

Open Tasks requests only:

`https://www.googleapis.com/auth/drive.appdata`

The app uses Google Identity authorisation from an explicit Privacy & recovery
action. It does not request sign-in or Drive access at startup. Consent copy
explains that encrypted workspace data is stored in a hidden app-specific Drive
folder and that removing the app's Drive access or deleting that folder can
remove cloud recovery.

The account binding stores only the minimum opaque account handle required to
request credentials. Access tokens remain in provider-managed credential
storage and memory. They are never written to Room, logs, saved instance state,
or the outbox.

Account replacement is never automatic. Choosing another account first offers
disconnect or migration recovery for the current vault.

## Transport

`CloudObjectStore` keeps bounded byte-array methods for manifest and operation
metadata and adds file/stream-based resumable upload and download for snapshots
and attachments.

The Drive implementation:

- Restricts every list/change request to `appDataFolder`.
- Requests only required response fields.
- Follows every page token until completion.
- Persists the new start-page token only after the full page sequence merges.
- Uses resumable upload sessions for large objects.
- Downloads to an app-private temporary file.
- Verifies length and SHA-256 before decrypting.
- Retries idempotent requests with exponential backoff and jitter.
- Treats missing app-data files as remote deletion input, not an empty vault.

HTTP and provider errors are mapped to stable domain categories:
authentication, offline, quota, rate limit, missing object, revision conflict,
checksum, incompatible format, and retry exhaustion.

## Sync coordinator

`DriveSyncCoordinator` implements the existing `SyncCoordinator` contract and
adds no UI dependency. It has one serialised run per vault.

A run performs:

1. Read the local sync cursor and pending outbox count.
2. Authorise or enter `Blocked(AUTHENTICATION)`.
3. Upload closed local operation segments.
4. Conditionally merge and publish the manifest.
5. List/download remote changes from the last committed cursor.
6. Validate and decrypt objects into a bounded staging representation.
7. Apply the complete remote batch in one Room transaction.
8. Rebuild derived task blocking, project progress, timers, conflicts, and
   snapshot state.
9. Mark uploaded operations and commit the new remote cursor.
10. Publish `Synced` or the precise remaining pending state.

Downloaded changes do not generate a new local outbox operation when their
revision already represents the merged value. A genuinely new local conflict
resolution receives a new local HLC revision and is uploaded normally.

## Merge semantics

- Scalar fields use highest HLC revision with device-ID tie-breaking.
- Set relations use timestamped membership operations.
- Tombstones dominate older upserts and remain until the defined retention
  gate.
- Workflow rank, milestone membership, recurrence metadata, reminders,
  templates, and dependencies retain their existing validation invariants.
- Time entries merge by entry identifier and revision. Concurrent entries are
  preserved; overlaps remain explicit conflicts.
- Unsupported or invalid remote graphs are quarantined before entering Room.
- Reapplying a complete remote batch is idempotent.

## Scheduling

Sync runs:

- At app startup when Drive-primary.
- After local edits through a debounced request.
- On user refresh.
- Periodically through WorkManager while network is available.
- During provider migration and recovery.

WorkManager uses network constraints, exponential backoff, and unique work per
vault. Manual refresh may run immediately. Retry exhaustion stops automatic
tight loops while preserving a manual retry.

## User experience

Home retains `SyncHealthChip`. Settings adds a Sync detail surface showing:

- Local-only, pending, running phase, synced, or blocked state.
- Pending operation count.
- Last successful sync time.
- Account display label supplied by Google Identity.
- Manual refresh, re-authorise, migrate, and disconnect actions.

User-facing text never displays Drive file identifiers, tokens, raw provider
messages, or cryptographic details. Offline and pending states are informative,
not modal; local work remains available.

## Test design

A deterministic fake `CloudObjectStore` covers:

- Multi-page changes and cursor commit.
- Duplicate, reordered, and redelivered segments.
- Concurrent manifest revision conflicts.
- Partial resumable upload/download.
- Process death between every sync phase.
- Authentication expiry and re-authorisation.
- Offline, quota, rate limiting, missing objects, and exhausted retries.
- Checksum, AEAD, unsupported version, and graph-validation failure.
- Two and three devices editing scalar, set, tombstone, recurrence, reminder,
  dependency, template, and time-entry records.

A credentialed external suite uses a dedicated test Google account and deletes
only opaque objects created under its test vault prefix.

## Exit criteria

- A local edit uploads and appears on a second device after deterministic
  merge.
- Offline editing and later sync lose no operation.
- Pagination, retry, redelivery, and process restart are idempotent.
- Sync state remains legible and local UI remains usable during every provider
  failure.
- Only encrypted objects enter Drive `appDataFolder`.
- No credential or private identifier enters repository logs or fixtures.
