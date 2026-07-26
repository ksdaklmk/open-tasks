# Threat Model

Last reviewed: 27 July 2026

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
| Task and project records, notes, checklist text and time data | Private content | SQLCipher Room database |
| Attachment content and names | Private content | Planned app-private encrypted files |
| Local database key | Critical key material | AES-GCM envelope in private preferences |
| Local wrapping key | Critical key material | Non-exportable Android Keystore entry |
| Cloud vault data key | Critical key material | Tink keyset wrapped by recovery passphrase |
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
    └── atomic outbox ─────────────── future SyncCoordinator
                                           │ encrypted objects only
                                           ▼
                                      Drive appDataFolder

Recovery passphrase ── Argon2id ── AES-GCM unwrap ── Tink vault data key
Android Keystore key ── AES-GCM unwrap ───────────── SQLCipher database key
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
| T02 | Keystore entry lost or invalidated | Existing envelope requires the existing alias; key manager fails closed and never creates a replacement | Local-only vault is unrecoverable until cloud recovery is implemented; UI recovery flow is required before release |
| T03 | Wrapped local key modified or partially stored | AES-GCM authentication, associated data, nonce validation and synchronous preference commit | Preferences and Keystore are not an atomic cross-store transaction; first-run interruption must remain recoverable |
| T04 | Recovery passphrase guessed offline | Argon2id with 64 MiB, three iterations, parallelism one and a random 16-byte salt | Password strength remains user-dependent; recovery UX needs strength guidance and rate-limited online flows |
| T05 | Recovery envelope or encrypted record modified | AES-GCM/Tink authentication; format-bound associated data; tamper and golden-vector tests | Future parsers must cap input sizes before allocation |
| T06 | Ciphertext moved between vaults, objects or chunks | Associated data binds vault ID, object ID, format version and chunk index | Every future cloud and attachment encryption call must use the canonical context |
| T07 | Key bytes remain in memory | Derived keys, temporary database keys and `VaultKey` buffers are explicitly zeroed | JVM/Android copies and immutable strings cannot be guaranteed erased; passphrases must remain `CharArray` |
| T08 | Android backup leaks vault content or keys | Backup disabled; extraction and transfer rules exclude the application root; legacy rules exclude databases, keys, vaults, attachments and key preferences | Re-test rules on supported API levels during release acceptance |
| T09 | Logs or telemetry leak private fields | Architecture prohibits task text, account data, Drive IDs, attachment names and encryption metadata; current scan found no application logging calls | Any telemetry integration requires a separate field allow-list review |
| T10 | Exported component mutates or leaks data | Only launcher activity is exported; custom quick-add intent opens authenticated UI; `FileProvider` is private and limited to `cache/shared` | Add explicit validation and user confirmation when Sharesheet/import flows are implemented |
| T11 | Drive provider reads cloud content | Planned Drive objects are encrypted locally; only `drive.appdata` scope is permitted | Drive transport and OAuth are not implemented; no cloud-security claim may be made yet |
| T12 | Replay, duplicate delivery or reordering creates divergent state | Atomic outbox, deterministic recurrence IDs, idempotent completion, hybrid logical clocks and deterministic merge rules | End-to-end pagination, retry and multi-device tests depend on the Drive slice |
| T13 | Malicious or incompatible cloud/import format | Version headers expose schema, crypto and minimum-reader versions; readers reject unsupported formats | Checksums, bounded decoding, quarantine and recovery UI remain to be implemented |
| T14 | Dependency or CI workflow compromise | Minimal `google()`/`mavenCentral()` repositories, read-only CI token, no CI secrets, automated dependency/security updates, secret scanning and push protection | GitHub Actions are tag-pinned rather than commit-pinned; pin before CI receives signing or OAuth secrets |
| T15 | Plaintext export or notification discloses content | Features are not implemented; product contract requires deliberate warnings and privacy controls | Threat review is mandatory when reminders, widgets, CSV or sharing are added |
| T16 | Screenshots reveal unlocked content | No app-wide screenshot blocking, by design | Planned app-lock title privacy must provide a user-controlled concealment option |

## Cryptographic invariants

- Local database and cloud data keys are independent random 256-bit values.
- A stored local key envelope must never cause creation of a new Keystore key.
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
3. Pin GitHub Actions to reviewed commit SHAs before CI receives secrets.
4. Add bounded parsers and quarantine/recovery UX for malformed cloud and
   import objects.
5. Re-run migration fixtures from every released database and crypto format.
6. Complete notification, export, attachment, widget and app-lock privacy
   reviews as those features are implemented.
7. Complete Privacy Policy, OAuth verification, Play Data Safety and signing
   operations outside the repository.
