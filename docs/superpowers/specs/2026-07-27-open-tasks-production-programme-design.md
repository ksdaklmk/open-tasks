# Open Tasks Production Programme Design

**Date:** 27 July 2026
**Status:** Approved design
**Target:** Globally available production release with staged updates

## Purpose

Complete the remaining Open Tasks P0–P3 roadmap from the current working tree
and deliver a production-ready, privacy-first Android application. The first
production publication is global because Google Play cannot percentage-stage a
new app's first production release. Closed and open testing contain that launch
risk; all later production updates use staged percentages.

This programme starts from the locally completed P1/P2 state recorded in
`HANDOFF.md`. It preserves and checkpoints those changes rather than
reimplementing them.

## Product contract

Open Tasks remains:

- Android-only, adaptive from compact phones through foldables and tablets.
- A solo-professional workspace without collaboration or a proprietary server.
- Free, without advertising, purchases, analytics, or crash-reporting SDKs.
- Local-first, with SQLCipher local authority or encrypted Drive-primary
  authority.
- Globally available with UK English (`en-GB`) as the only v1 locale.
- Useful while offline and explicit about sync, recovery, conflicts, and risk.

The v1 release does not add collaboration, a web client, iOS support,
background two-way calendar sync, plaintext cloud data, or an alternate
analytics data store.

## Programme-wide engineering constraints

- Preserve `minSdk 36`, `compileSdk 37`, `targetSdk 37`, Java 17, the JDK 21
  daemon toolchain, and AGP built-in Kotlin.
- Keep Navigation 3, `WorkspaceLayoutPolicy`, stateless feature composables,
  and app-layer command dispatch.
- Route every durable mutation through a typed `DomainCommand` and
  `VaultRepository.execute`.
- Commit each mutation and its outbox operation atomically.
- Use repository-produced Undo for reversible commands.
- Keep `InMemoryVaultRepository` behaviour aligned with
  `RoomVaultRepository`.
- Keep the SQLCipher database and cloud vault-content key independent.
- Zero temporary key arrays and retain passphrases as `CharArray`.
- Never put keys, passphrases, attachment content, or vault payloads in saved
  instance state.
- Never log private content, filenames, account details, Drive identifiers,
  ciphertext, keys, or encryption metadata.
- Require a non-destructive migration and exported Room schema for every
  database version.
- Require old-format fixtures and golden-vector review for every crypto-format
  change.
- Put new copy in resources and retain UK spelling, day–month dates, 24-hour
  times, localisation readiness, and RTL-safe layout.
- Update architecture, design, threat model, README, and handoff documents in
  the same change whenever their contracts change.

## System architecture

The existing module graph remains authoritative. New code is placed in focused
files inside current modules, except for the dedicated Baseline Profile and
Macrobenchmark modules required by production qualification.

`VaultRepository` remains the only product-data mutation boundary. Local UI
commands and downloaded remote changes use different execution paths:

```text
Compose feature UI
    │ callbacks
    ▼
app coordinators / ViewModels
    │ DomainCommand
    ▼
VaultRepository.execute
    │
    ├── record transaction + outbox append
    └── immutable WorkspaceSnapshot

WorkManager / manual refresh
    │
    ▼
SyncCoordinator
    ├── upload pending encrypted operations
    ├── download encrypted remote objects
    └── internal remote-batch merge transaction
            └── no new upload operation for unchanged remote data
```

Remote merge is an internal repository collaborator, not a UI command. This
prevents downloaded operations from recursively returning to the outbox.

Large existing files are split only when their train touches the corresponding
responsibility:

- `MoreScreen.kt`: Insights, Settings, Privacy/Recovery, Templates, Archive,
  and Bin.
- `TasksScreen.kt`: core editor, recurrence, dependencies, time, activity, and
  attachments.
- `OpenTasksApp.kt`: Navigation 3 shell, platform launchers, and destination
  binding.
- `WorkspaceViewModel.kt`: workspace commands plus focused sync, recovery, and
  attachment coordinators.
- `RoomVaultRepository.kt`: command execution, snapshot assembly, outbox
  encoding, and remote merge.

## Data authority

Exactly one vault is active:

- In `LOCAL`, SQLCipher and encrypted app-private files are durable authority.
- In `DRIVE_PRIMARY`, the same local state is the immediate offline cache and
  atomic outbox; encrypted Drive app-data objects are durable authority.

The vault-content key is random, independent from the SQLCipher key, and
wrapped locally by Android Keystore. Drive migration adds a passphrase-wrapped
recovery envelope for that same key. Local-only attachments can therefore be
encrypted without forcing Drive enrolment, while later migration does not
reencrypt every attachment.

Drive uses four encrypted object families:

1. A bounded manifest with compatibility and recovery-envelope metadata.
2. Periodic complete workspace snapshots.
3. Immutable per-device operation segments ordered by hybrid logical clock.
4. Opaque, chunked attachment objects.

SHA-256 ciphertext checksums detect incomplete transport. Tink AEAD associated
data binds vault, object, format, and chunk identity. Unsupported, oversized,
damaged, or unauthenticated objects are quarantined before merge.

## Product surfaces

The five top-level destinations remain unchanged:

- Home retains daily focus, a compact insight summary, and sync health.
- Tasks owns canonical task editing, activity, notes, files, and time history.
- Projects adds project activity context and filtered-insight entry points.
- Schedule remains a read-only snapshot projection and offers explicit
  one-way calendar insertion.
- More owns Insights, Templates, Archive, Bin, Settings, and Privacy &
  recovery.

All charts have equivalent ordered tables or text summaries. Meaning never
depends on colour. Restored routes, selections, filters, scroll positions,
drafts, and open sheets use the existing layered restoration contract.

## Failure and privacy model

Local editing continues when Drive is offline, unauthorised, over quota, or
temporarily failing. Sync exposes explicit offline, authentication, quota,
checksum, decryption, incompatible-format, and retry-exhausted states.

Migration switches authority only after an encrypted upload/download/decrypt
round-trip passes. Disconnect materialises and verifies the complete vault
locally before switching to `LOCAL`; it does not delete Drive content. Cloud
deletion is a separate passphrase-confirmed action.

Attachment intake streams into bounded temporary storage, encrypts and verifies
chunks, atomically publishes the file, and only then registers metadata and an
outbox operation. Failure removes temporary state. Startup cleanup removes
abandoned intake and share files.

Recovery passphrases require at least 12 characters, use no composition rules,
include confirmation and strength guidance, and may be generated locally as a
multi-word phrase. App lock uses Android biometric or device credential as an
access gate rather than a replacement encryption scheme.

Encrypted `.otvault` import validates in an isolated staging vault and activates
only after confirmation. Plaintext CSV is export-only in v1 and presents a new
disclosure for each export.

## Release trains

| Train | Scope | Primary backlog |
|---|---|---|
| 0 | Baseline and delivery pipeline | P0-R08, P3-T00–P3-T03 |
| 1 | Insights and cloud-format foundation | P2-F04, P1-D02 |
| 2 | Drive identity and core sync | P1-D01, P1-D03, P1-D04 |
| 3 | Migration and recovery | P1-D05, P1-D06, P0-R03, P0-R09 |
| 4 | Notes, activity, attachments, and search | P2-F02, P1-D07, P1-L05 |
| 5 | Remaining workspace and platform features | P1-L08, P2-F05–P2-F07 |
| 6 | Production qualification and rollout | P1-D08, P0-R02, P0-R04–P0-R07, P0-R10 |

The dependency chain is:

```text
Train 0 → Train 1 → Train 2 → Train 3 → Train 4 → Train 5 → Train 6
```

Train 1 Insights can ship independently, but its cloud-format work gates Train
2. Train 4 resolves the former circular dependency between local attachments
and cloud attachment transport by defining one attachment contract before
implementing either side. Train 5 waits for the final local schema. Train 6
qualifies the complete product.

## Verification strategy

- JVM tests cover pure domain logic, codecs, checksums, parsers, merge rules,
  cache policy, retries, import/export, and failure classification.
- A shared repository contract suite runs against in-memory and encrypted Room
  implementations.
- Room device tests cover migrations, restarts, atomic writes, remote merge,
  rollback, and malformed-input rejection.
- Fake Drive tests inject pagination, duplicates, ordering changes, expired
  authentication, quota, partial transfer, checksum failure, and network loss.
- Credentialed tests exercise the real Drive `appDataFolder`.
- Compose semantics and behaviour tests cover all new feature surfaces.
- Pinned official Compose preview screenshot tests cover representative
  light/dark, compact/medium/expanded, font-scale, and RTL configurations.
- Manual physical-device acceptance covers fold posture, tablets, split
  screen, live resizing, keyboard/mouse, TalkBack, Switch Access, high
  contrast, reduced motion, notifications, widgets, sharing, and recovery.
- Baseline Profiles and Macrobenchmarks measure startup and critical journeys
  on a physical device with a large encrypted fixture.

## Production delivery

The Play path is internal testing, required closed testing, production-access
approval where applicable, globally available open testing, and global
production publication. A qualifying newer personal account must retain at
least 12 opted-in closed testers for 14 continuous days.

Google Play does not support percentage staging for an app's first production
publication. The first release is therefore contained through closed/open
testing and then published globally. Later updates progress through 5%, 20%,
50%, and 100%, with explicit observation and halt gates.

Drive requests only the non-sensitive `drive.appdata` scope. OAuth production
branding, correct signing-certificate fingerprints, privacy policy, Data
Safety, app-content declarations, Play App Signing, upload-key custody, and
verified developer details are release gates.

## Definition of production complete

- Every non-optional remaining P0–P3 item is implemented or has passed its
  external acceptance gate.
- Unit, lint, debug, release, migration, crypto, cloud, accessibility,
  screenshot, and performance gates pass.
- No open critical/high security issue, data-loss issue, blocking
  accessibility failure, or incompatible-format gap remains.
- Reinstall, new-device, and Keystore-loss recovery are exercised.
- Internal, required closed, and open testing are complete.
- The initial global production release is approved and published.
- The staged-update, monitoring, halt, and rollback procedures are verified.

## Focused specifications

- [Train 0 — Baseline and delivery](2026-07-27-train-0-baseline-delivery-design.md)
- [Train 1 — Insights and cloud format](2026-07-27-train-1-insights-cloud-format-design.md)
- [Train 2 — Drive identity and core sync](2026-07-27-train-2-drive-sync-design.md)
- [Train 3 — Migration and recovery](2026-07-27-train-3-migration-recovery-design.md)
- [Train 4 — Notes, attachments, and
  search](2026-07-27-train-4-notes-attachments-search-design.md)
- [Train 5 — Platform features](2026-07-27-train-5-platform-features-design.md)
- [Train 6 — Production qualification and
  rollout](2026-07-27-train-6-production-qualification-rollout-design.md)

## Authoritative external constraints

- [Drive application-data folder](https://developers.google.com/workspace/drive/api/guides/appdata)
- [OAuth verification](https://support.google.com/cloud/answer/13463073)
- [Google Play testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Google Play staged rollouts](https://support.google.com/googleplay/android-developer/answer/6346149)
- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Google Play Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Google Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Compose screenshot testing](https://developer.android.com/studio/preview/compose-screenshot-testing)
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)
- [Macrobenchmark measurement](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile)
