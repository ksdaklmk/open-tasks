# Train 3 — Migration and Recovery Design

> **Superseded — 28 July 2026:** Do not execute this train. The approved
> local-authority, backup, recovery-takeover, and cloud-attachment direction is
> defined in the 28 July design and the live production master plan.

## Goal

Implement P1-D05, P1-D06, P0-R03, and P0-R09: verified transitions between
local and Drive-primary authority plus recovery from reinstall, a new device,
and local Keystore loss.

## Local-to-Drive migration

Migration is an explicit wizard in Privacy & recovery:

1. Explain authority, offline behaviour, Drive deletion risk, and recovery.
2. Authorise the chosen Google account.
3. Create or confirm a recovery passphrase.
4. Produce a complete encrypted snapshot and attachment inventory.
5. Upload manifest, snapshot, operations, and attachments.
6. Download every published object into staging.
7. Verify checksum, decrypt, decode, and compare record/attachment inventories.
8. Commit `StorageMode.DRIVE_PRIMARY` and the remote cursor.
9. Retain an encrypted local rollback copy for seven days.

The mode switch is the final transaction. Cancellation, process death, quota,
network loss, or verification failure leaves `LOCAL` authoritative and removes
only safe temporary remote objects.

## Seven-day rollback

The rollback copy contains the pre-migration SQLCipher database, encrypted
attachment files, and a small integrity manifest. Its key remains protected by
the existing local Keystore boundary.

Privacy & recovery shows the rollback expiry date and a Roll back action while
available. Successful rollback closes active repository/sync scopes, verifies
the copy, atomically replaces active local files, reopens Room, and leaves the
Drive objects untouched. Startup cleanup deletes the rollback copy after seven
full days.

## Drive-to-local disconnect

Disconnect:

1. Pauses new sync work.
2. Flushes pending local operations.
3. Downloads the current manifest, snapshot, remaining operation segments, and
   required attachment content.
4. Applies and verifies the complete merged vault locally.
5. Confirms sufficient device storage.
6. Switches to `LOCAL`.
7. Revokes or forgets the account binding only after local verification.

Disconnect never deletes Drive data. A separate Delete cloud copy action
requires recovery-passphrase confirmation, a typed irreversible warning, and a
fresh object inventory. It deletes only objects belonging to the active vault.

## Recovery entry points

Recovery is offered when:

- The local Keystore envelope cannot be opened.
- The app is reinstalled and no local vault exists.
- A user deliberately chooses Restore from Drive on a new device.

The user authorises Drive, selects the discovered opaque vault by safe metadata
such as creation time, and enters the recovery passphrase. The app:

1. Validates the manifest and KDF bounds.
2. Unlocks the vault-content key in memory.
3. Downloads and validates the newest compatible snapshot and all later
   operation segments.
4. Falls back to the previous snapshot if the current snapshot is damaged.
5. Reconstructs a new SQLCipher database and encrypted attachment cache in
   staging.
6. Verifies counts, identifiers, relations, and attachment inventory.
7. Creates a new local Keystore wrapper for the recovered content key and an
   independent new SQLCipher key.
8. Atomically activates the staged vault.

A wrong passphrase, weakened KDF metadata, damaged object, unsupported reader
version, or insufficient storage leaves the current installation unchanged.

## Recovery UX

Passphrases require at least 12 characters, confirmation, no arbitrary
composition rule, and clear offline-guessing guidance. A generated multi-word
phrase is created locally and can be copied only through an explicit action.
The passphrase is never retained after the operation.

Recovery progress reports high-level phases and cancellability. It never shows
Drive IDs, ciphertext properties, or cryptographic metadata. Cancellation is
allowed before activation; activation itself is an atomic non-cancellable
step.

## Core cloud acceptance matrix

The train completes end-to-end tests for:

- Simultaneous edits from two and three devices.
- HLC wall-clock rollback.
- Duplicate and reordered operation delivery.
- Authentication expiry during upload and download.
- Quota exhaustion before and during migration.
- Pagination across all object families.
- Corrupt current snapshot with valid previous snapshot.
- Corrupt operation segment and quarantined cursor.
- App reinstall and new-device recovery.
- Local Keystore loss with valid and missing recovery envelopes.
- Interrupted migration, rollback, disconnect, and recovery.
- Drive app-data folder deletion by the user.

Attachment payload recovery remains completed in Train 4, but the inventory,
streaming, and staging seams are exercised here with encrypted fixtures.

## Exit criteria

- Authority never switches before a verified round-trip.
- Rollback restores the exact pre-migration local vault for seven days.
- Disconnect yields a complete local vault without deleting cloud content.
- Reinstall, new-device, and Keystore-loss recovery succeed with the correct
  passphrase and fail closed otherwise.
- The core multi-device P0 matrix passes without silent data loss.
- Threat-model release gates T02–T06 and T11–T13 are updated with evidence.
