# Threat Model

Last reviewed: 28 July 2026

This document covers the implemented local-first foundation and the planned
Drive-primary architecture. It is a release gate: any new data flow, exported
Android component, cloud object, attachment path, logging sink, or key format
must update this model before release.

## Scope and security objectives

Open Tasks stores private task, project, note, checklist, schedule, time and
attachment data. The security objectives, in order, are:

1. Keep vault content confidential at rest and in cloud storage.
2. Detect modification, object swapping, truncation and incompatible formats.
3. Preserve a recoverable, deterministic history across retries and devices.
4. Avoid silently replacing or discarding cryptographic keys.
5. Prevent logs, backups, exports and shared files from bypassing the vault.

The current application is local authority only. Google Identity, Drive
transport, attachment import/export and cloud recovery have not been connected.
Those planned flows are included below so their implementation has explicit
security gates.

## Assets

| Asset | Sensitivity | Current location |
|---|---|---|
| Task, project and template records, reminders, notes, checklist text and time data | Private content | SQLCipher Room database |
| Unsaved editor, quick-add and search text plus UI identifiers | Private transient content and sensitive metadata | Android saved-instance-state bundle |
| Attachment content and names | Private content | Planned app-private encrypted files |
| Local database key | Critical key material | AES-GCM envelope in private preferences |
| Local wrapping key | Critical key material | Non-exportable Android Keystore entry |
| Vault-content key | Critical key material | Tink keyset independently wrapped by the recovery passphrase and a per-vault Android Keystore key |
| Recovery passphrase | User-held secret | Memory only; never persisted |
| Device ID, revisions and sync clocks | Sensitive metadata | Private preferences/database |
| Drive object identifiers and account data | Sensitive metadata | Not implemented |
| Build, signing and OAuth credentials | Release secrets | Not present in this repository |

## Trust boundaries and data flows

```text
Compose UI
    │ typed actions
    ▼
app command dispatch
    │ DomainCommand
    ▼
VaultRepository
    ├── SQLCipher Room transaction ── local encrypted database
    ├── opaque reminder IDs ───────── Android AlarmManager
    │                                      │ private PendingIntent
    │                                      ▼
    │                                private notification channel
    └── atomic outbox ─────────────── future SyncCoordinator
                                           │ encrypted objects only
                                           ▼
                                      Drive appDataFolder

Recovery passphrase ── Argon2id ── AES-GCM unwrap ── Tink vault-content key
Per-vault Android Keystore key ─── AES-GCM unwrap ── same content key
Database Android Keystore key ──── AES-GCM unwrap ── SQLCipher database key
```

The repository is the only data-write boundary. A mutation and its outbox
operation must commit atomically. UI and future transport code must never
write Room records directly. Future Drive code may handle encrypted objects
and limited routing metadata, but must not receive plaintext record content or
the recovery passphrase.

## Adversaries and assumptions

The model considers:

- a lost, stolen or shared device which is locked or subsequently unlocked;
- another application attempting to invoke exported components or consume
  shared files;
- corrupted or malicious imported files and cloud objects;
- a compromised Drive account or storage service;
- retry, replay, reordering, clock rollback and conflicting multi-device
  operations;
- accidental data disclosure through logs, Android backup, screenshots,
  notifications or plaintext export;
- dependency and CI supply-chain compromise.

The following are outside the confidentiality guarantee: a rooted device, a
compromised operating system, an attacker observing plaintext while the user
has unlocked the device and application, or a malicious accessibility service
authorised by the user. The app should still fail safely where practical and
must not weaken platform protections.

## Threats, controls and residual risk

| ID | Threat | Implemented control | Residual risk or required gate |
|---|---|---|---|
| T01 | Database copied from a locked device | SQLCipher; random 256-bit database key; key wrapped by an AES-GCM Keystore key requiring an unlocked device | Rooted or compromised OS is out of scope |
| T02 | Keystore entry lost, replaced or invalidated | Database and per-vault content-key envelopes require their existing aliases; managers fail closed and never create a replacement for stored state; failed content-key alias deletion restores the captured preference envelope before propagating failure | Local-only vault is unrecoverable until cloud recovery is implemented; UI recovery flow is required before release |
| T03 | Wrapped local key modified or partially stored | AES-GCM authentication, versioned exact-vault associated data, strict nonce/envelope validation, synchronous preference commits and best-effort rollback to the captured prior content-key envelope | Preferences and Keystore are not an atomic cross-store transaction; a process or device loss between stores can leave an orphan alias, and first-run interruption must remain recoverable |
| T04 | Recovery passphrase guessed offline | Argon2id with 64 MiB, three iterations, parallelism one and a random 16-byte salt | Password strength remains user-dependent; recovery UX needs strength guidance and rate-limited online flows |
| T05 | Recovery envelope or encrypted record modified | AES-GCM/Tink authentication; format-bound associated data; tamper and golden-vector tests | Future decrypted-payload parsers must cap input sizes before allocation |
| T06 | Ciphertext moved between vaults, objects or chunks | `VaultCrypto` supports canonical associated data for record identity; cloud headers now preserve exact family, versions, vault/object IDs and optional chunk identity | Task 1.6 must bind the complete `CloudHeaderIdentity` during AEAD encrypt/decrypt; every future attachment call must use the same canonical context |
| T07 | Key bytes remain in memory | Derived keys, temporary database keys and `VaultKey` buffers are explicitly zeroed | JVM/Android copies and immutable strings cannot be guaranteed erased; passphrases must remain `CharArray` |
| T08 | Android backup leaks vault content or keys | Backup disabled; extraction and transfer rules exclude the application root; legacy rules exclude databases, keys, vaults, attachments and key preferences | Re-test rules on supported API levels during release acceptance |
| T09 | Logs or telemetry leak private fields | Architecture prohibits task text, account data, Drive IDs, attachment names and encryption metadata; current scan found no application logging calls | Any telemetry integration requires a separate field allow-list review |
| T10 | Exported component mutates or leaks data | Only launcher activity is exported; reminder action/system receivers are private; reminder pending intents are immutable and carry opaque IDs; custom quick-add intent opens authenticated UI; `FileProvider` is private and limited to `cache/shared` | Add explicit validation and user confirmation when Sharesheet/import flows are implemented |
| T11 | Drive provider reads cloud content | Planned Drive objects are encrypted locally; only `drive.appdata` scope is permitted | Drive transport and OAuth are not implemented; no cloud-security claim may be made yet |
| T12 | Replay, duplicate delivery or reordering creates divergent state | Atomic outbox, deterministic recurrence IDs, idempotent completion, revisioned project-workflow operations, hybrid logical clocks and deterministic merge rules | End-to-end pagination, retry and multi-device tests depend on the Drive slice |
| T13 | Malicious or incompatible cloud/import format | Strict canonical UTF-8 v1 headers expose schema, crypto and minimum-reader versions; readers reject unsupported/non-canonical fields, malformed identity, invalid chunk tuples, checksum mismatch, trailing/truncated bytes and family-specific lengths before exposing a one-shot ciphertext buffer; allocation is bounded before read and caller-controlled streams never receive the retained verified buffer | SHA-256 detects corruption but is not authentication. Task 1.6 must authenticate the complete header identity before plaintext use; bounded payload decoders, quarantine and recovery UI remain required |
| T14 | Dependency or CI workflow compromise | Minimal `google()`/`mavenCentral()` repositories, read-only CI token, no CI secrets, automated dependency/security updates, secret scanning and push protection; third-party Actions are pinned to reviewed commit SHAs | Review each future Action revision before changing its SHA, especially before CI receives signing or OAuth secrets |
| T15 | Plaintext export or notification discloses content | Reminder permission is requested in context; app and channel state are checked; lock-screen visibility is private with a generic public version; alarm intents exclude task text; precise timing has an explained inexact fallback | An unlocked notification shade contains task title and due/project context by design; verify lock-screen redaction and denial/settings flows on physical devices. Widgets, CSV and sharing still require separate reviews |
| T16 | Screenshots reveal unlocked content | No app-wide screenshot blocking, by design | Planned app-lock title privacy must provide a user-controlled concealment option |
| T17 | Unbounded or malformed workflow edits exhaust storage or corrupt reporting | Status names are trimmed and capped at 64 characters; active statuses are capped at 20 per project; repository validation preserves at least one active status in each semantic category; archive retains assigned records | Future import and Drive decoders must enforce the same limits before allocation or merge |
| T18 | Malformed milestone edits create cross-project references, unbounded rows or orphaned task membership | Names are trimmed and capped at 120 characters; projects are capped at 100 milestones; repository validation enforces project ownership; delete/restore revises assigned tasks and milestone outbox state atomically | Future import and Drive decoders must enforce identical ownership and allocation limits before merge |
| T19 | Malformed dependency edits create cycles, self-links, unbounded graph traversal or silent blocked-task completion | Repository commands cap dependencies at 100 per task, reject self/transitive cycles before writing, derive unresolved links from durable relations and require explicit blocked-completion acknowledgement; the relation, revised task and full dependency-set outbox payload are atomic | Future import and Drive merge must apply the same bounds and cycle validation before accepting a remote graph |
| T20 | Process restoration duplicates private draft text outside SQLCipher or accidentally retains key material | Saveable state is limited to bounded UI text, route/filter state and record IDs; keys, passphrases, attachment bytes and vault payloads are prohibited; Android owns the transient bundle inside the device credential boundary; backup remains disabled | Saved-instance state is not protected by the vault's SQLCipher key. A compromised OS or an attacker observing an unlocked device remains out of scope; new sensitive input surfaces require a restoration review |
| T21 | Malformed or oversized templates exhaust memory, create cyclic relationships, cross workspace boundaries or lose structure during future sync | Capture is capped at 100 templates, 500 tasks, a 100-year date span and 2 MiB per payload; the versioned decoder validates bounded identifiers/text/collections, zones, semantic workflow coverage, workspace identity and acyclic parent/dependency graphs before use; instantiation and outbox writes are atomic; task outbox v5 carries start and due moments | Future Drive merge must authenticate the enclosing object, apply the same decoder before allocation/merge and quarantine unsupported or damaged payloads; the current UI does not yet provide a damaged-object recovery surface |
| T22 | Malformed or conflicting time entries create negative duration, unbounded overlap work, hidden double-counting or private-note disclosure | Repository commands require a positive interval, cap notes at 500 characters and entries at 10,000 per task, commit record/outbox changes atomically and provide exact Undo; deterministic linear reconciliation preserves entries and exposes overlaps in text/icon UI; SQLCipher protects local notes and delimiter-safe outbox v2 encoding prevents field injection | Future Drive decoding must enforce identical bounds before allocation, authenticate/encrypt operation payloads and apply revision ordering; concurrent overlaps require explicit user correction and remain visible until resolved |

## Cryptographic invariants

- Local database and vault-content keys are independent random 256-bit values.
- The Tink vault-content key is independently wrapped for recovery and local
  per-vault Android Keystore access; neither envelope uses the SQLCipher key.
- A stored database or content-key envelope must never cause creation of a new
  Keystore key.
- Recovery envelopes use format version 1 and reject weakened Argon2 metadata.
- Argon2id parameters are 65,536 KiB, three iterations and parallelism one.
- AES-GCM uses 96-bit nonces and 128-bit authentication tags.
- Record associated data encoding is covered by a golden vector.
- Changing a recovery passphrase re-wraps the existing data key; it does not
  re-encrypt every record.
- A database schema bump requires a non-destructive migration and exported Room
  schema. A cryptographic format bump requires explicit old-format fixtures.

## Dependency review

The 27 July 2026 review covered direct dependencies, Gradle repositories,
Android manifests, backup paths, logging calls and the CI workflow.

| Dependency | Reviewed version | Decision |
|---|---:|---|
| Android Room | 2.8.4 | Retain; current released Room line used by the project. Rehearse every schema migration on-device. |
| SQLCipher for Android | 4.15.0 | Retain; matches the current release shown by the upstream repository. Do not accept unrelated version-detector suggestions without upstream confirmation and encrypted migration tests. |
| Google Tink Android | 1.23.0 | Retain; golden, associated-data, wrong-passphrase, cross-device and tamper tests pass. |
| Bouncy Castle `bcprov-jdk18on` | 1.84 | Retain for this gate; upstream notes include the fixes relevant to the immediately preceding releases. Evaluate 1.85 separately with the Argon2 golden vector. |
| AndroidX, Compose, Hilt, Kotlin and AGP | Version catalogue | No security-motivated change identified. Keep update proposals small and run unit, lint, release and device gates for each. |

Reference sources:

- [Android Room releases](https://developer.android.com/jetpack/androidx/releases/room)
- [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android)
- [Google Tink releases](https://github.com/tink-crypto/tink/releases)
- [Bouncy Castle release notes](https://github.com/bcgit/bc-java/blob/main/docs/releasenotes.html)

Dependabot checks Gradle and GitHub Actions weekly. Dependency changes must not
be merged solely because a newer version exists. Cryptography, database and
compiler upgrades require focused compatibility tests and a fresh threat-model
review.

## Security acceptance gates

Before a production release:

1. Implement and test recovery from reinstall, new device and Keystore loss.
2. Complete Drive authentication, quota, pagination, corruption, replay and
   multi-device conflict tests using encrypted fixtures.
3. Retain reviewed GitHub Actions commit-SHA pins and review each update before
   CI receives secrets.
4. Complete bounded decrypted-payload/import parsers and quarantine/recovery UX
   for malformed objects; retain the implemented bounded outer cloud frame.
5. Re-run migration fixtures from every released database and crypto format.
6. Complete physical-device reminder lock-screen and permission acceptance;
   complete separate export, attachment, widget and app-lock privacy reviews
   as those features are implemented.
7. Complete Privacy Policy, OAuth verification, Play Data Safety and signing
   operations outside the repository.
