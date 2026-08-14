# Threat Model

Last reviewed: 6 August 2026

This document covers the implemented local-authority foundation and the
approved backup, recovery-takeover, and cloud-attachment programme. It is a
release gate: any new data flow, exported Android component, cloud object,
attachment path, logging sink, or key format must update this model.

## Scope and security objectives

Open Tasks stores private task, project, note, checklist, schedule, time, and
attachment data. The security objectives, in order, are:

1. Keep vault content confidential at rest and in cloud storage.
2. Detect modification, object swapping, truncation, and incompatible formats.
3. Preserve verified recovery data without creating a second active writer.
4. Avoid silently replacing or discarding cryptographic keys.
5. Keep attachment-byte failure isolated from structured local work.
6. Prevent logs, Android backup, exports, and shared files from bypassing the
   vault.

The current application has one structured-data authority: encrypted Room.
Room v8, local generation journalling, strict snapshot/segment payloads, and a
verified encrypted no-backup recovery-object pipeline are implemented. The
application now triggers that coordinator, verifies a recovery envelope, and
atomically publishes one portable encrypted package at most 24 MiB. Android
Auto Backup and device transfer include only that exact package. Room,
WAL/SHM, preferences, Keystore material, credentials, device identity, cache,
local staging, and attachment bytes remain excluded.

Package readiness proves only local production and eligibility. It does not
prove Android upload. Explicit Google authorization, create-only
`drive.appdata` transport, one-writer publication, lifecycle management, and
staged recovery/takeover are implemented in source. Credentialed
two-installation upload/restore was not claimed by completed Task 14; it
remains post-Stage-4 external qualification.
Task 13 is complete and review-clean: transient transport failure has truthful
bounded retry guidance without a Sign in claim, and genuine `MainActivity`
production recovery-route recreation proves private passphrase input is not
restored. Android-restored packages remain inert until the explicit recovery
path verifies and activates them. Attachment transfer is implemented as a
separate create-only blob namespace; remote merge is not connected.

## Assets

| Asset | Sensitivity | Current or approved location |
|---|---|---|
| Structured workspace records and tombstones | Private content | SQLCipher Room; sole live authority |
| Note text | Private content | SQLCipher Room and encrypted structured backup records |
| Immutable activity entries | Private content and metadata | SQLCipher Room and encrypted structured backup records |
| Unsaved editor, quick-add and search text plus UI identifiers | Private transient content and sensitive metadata | Android saved-instance-state bundle |
| Attachment metadata and opaque blob references | Private content and metadata | SQLCipher Room and encrypted structured backups |
| Attachment-transfer session state | Sensitive routing metadata | SQLCipher Room; exact generated IDs only |
| Attachment bytes | Private content | Encrypted attachment blob service; bounded ciphertext cache and plaintext staging only |
| Backup snapshots and journal segments | Private content | Implemented encrypted local objects under `noBackupFilesDir/backup/v1`; provider namespace remains Stage 3 |
| Portable backup package | Private encrypted recovery input | Implemented single Auto Backup-eligible app file |
| Local database key | Critical key material | AES-GCM envelope in private preferences |
| Local wrapping key | Critical key material | Non-exportable Android Keystore entry |
| Vault-content key | Critical key material | Independently wrapped by recovery passphrase and per-vault Keystore key |
| Recovery passphrase | User-held secret | Memory only; never persisted |
| Writer epoch and opaque device identity | Sensitive metadata | Encrypted local state and authenticated control manifest; never Android backup |
| Provider object identifiers and account data | Sensitive metadata | Private encrypted operation/configuration state or memory-only authorized session; never logs or Android backup |
| Build, signing, and OAuth credentials | Release secrets | Not present in this repository |
| Today-widget state (task titles) | Private content, plaintext at rest, concealment-gated | Glance state files under `filesDir`; cleared through `StopGatedWriter` on a qualifying lock or title-privacy transition; excluded from Android Auto Backup and device transfer |
| Whole-vault archive (`.otvault`) and its export passphrase | Private encrypted recovery input; a recovery-equivalent secret | The archive is a standard file the person chooses a destination for; the export passphrase is the same recovery passphrase and is never persisted |
| Exported plaintext CSV file | Private content, disclosed plaintext once written | A standard file the person chooses a destination for, after a mandatory disclosure dialog; not encrypted and not tracked further by the application |

## Trust boundaries and data flows

```text
Compose UI
    │ typed actions
    ▼
app command dispatch
    │ DomainCommand
    ▼
VaultRepository
    ├── SQLCipher Room transaction ─────── sole live authority
    ├── opaque reminder IDs ───────────── Android AlarmManager
    └── atomic BackupJournal entry ────── implemented local generation record

BackupCoordinator ── AuthenticatedCloudObjectCodec ── LocalBackupObjectStore
PortableBackupPublisher ───────────────────────────── portable package
AttachmentBlobCoordinator ─ AuthenticatedCloudObjectCodec ─ AttachmentBlobStore
RecoveryCoordinator ─ staged verification/takeover ─ replacement Room vault

Recovery passphrase ── Argon2id ── AES-GCM unwrap ── vault-content key
Per-vault Keystore key ─────────── AES-GCM unwrap ── same content key
Database Keystore key ──────────── AES-GCM unwrap ── SQLCipher database key
```

`VaultRepository` is the only normal structured-data write boundary. A mutation
and its journal representation commit atomically. The additive v5→v6 migration
preserves existing outbox rows and copies deterministic legacy journal entries;
the legacy table is read-only and its coverage marker advances only after a
verified complete baseline.

Normal provider flows may handle encrypted objects and limited routing
metadata but cannot mutate Room records. Only `RecoveryCoordinator` may
reconstruct a replacement database, and it must stage, verify, claim writer
ownership where applicable, and activate atomically.

## Adversaries and assumptions

The model considers:

- a lost, stolen, or shared device which is locked or later unlocked;
- another application invoking exported components or consuming shared files;
- corrupted or malicious imported files, backup objects, portable packages,
  and attachment chunks;
- a compromised provider account or storage service;
- retry, replay, reordering, quota exhaustion, and clock rollback;
- two devices attempting to publish the same backup lineage or blob set;
- stale-writer activity after recovery takeover;
- premature blob deletion or retention inventory corruption;
- accidental disclosure through logs, Android backup, screenshots,
  notifications, or plaintext export; and
- dependency and CI supply-chain compromise.

A rooted device, compromised operating system, attacker observing plaintext
while the user has unlocked the app, or malicious accessibility service
authorised by the user is outside the confidentiality guarantee. The app still
fails safely where practical and must not weaken platform protections.

## Threats, controls, and required gates

| ID | Threat | Implemented control | Residual risk or required gate |
|---|---|---|---|
| T01 | Database copied from a locked device | SQLCipher; random 256-bit database key; key wrapped by an AES-GCM Keystore key requiring an unlocked device | Rooted or compromised OS is out of scope |
| T02 | Keystore entry lost, replaced, or invalidated | Stored database and content-key envelopes require existing aliases; managers fail closed and never create replacement keys for stored state | Local vault remains unrecoverable until Stage 3 recovery passes reinstall, new-device, and Keystore-loss gates |
| T03 | Wrapped key modified or partially stored | AES-GCM authentication, exact-vault associated data, strict envelope validation, synchronous preference commits, and rollback where possible | Preferences and Keystore are not atomic; first-run interruption and orphan aliases remain test cases |
| T04 | Recovery passphrase guessed offline | Argon2id with 64 MiB, three iterations, parallelism one, and random 16-byte salt | Password strength remains user-dependent; future UI requires strength guidance |
| T05 | Encrypted record modified | AES-GCM/Tink authentication, format-bound associated data, tamper tests, and golden vectors | Every future decrypted parser must retain allocation bounds |
| T06 | Ciphertext moved between vaults, objects, or chunks | The implemented authenticated codec binds the complete canonical family, version, vault/object, and optional chunk identity as AEAD associated data before plaintext use | Future family payload decoders must preserve the same identity and allocation bounds |
| T07 | Key bytes remain in memory | Derived keys, temporary database keys, and `VaultKey` buffers are explicitly zeroed | JVM/Android copies and immutable strings cannot be guaranteed erased; passphrases stay `CharArray` |
| T08 | Android backup leaks live vault, keys, or blobs | Packaged extraction rules include only the `file`-domain application-relative path `android_backup/open_tasks_portable_v1.otb`; database, preferences, root, cache, local staging, keys, credentials, and attachment paths remain excluded | Real encrypted Google-account transport inclusion and restore remain an external qualification gate |
| T09 | Portable package includes excluded data or grows beyond safe platform bounds | The publisher builds from a consistent snapshot, verifies the authenticated container, caps it at 24 MiB, withdraws ineligible generations, and publishes atomically | Future format changes require the same exact-file and bounded-package audit |
| T10 | Logs or telemetry leak private fields | Architecture prohibits private content and sensitive routing data; current review found no application logging calls | Any telemetry requires a separate field allow-list review |
| T11 | Exported component mutates or leaks data | `MainActivity` is exported for the launcher, the existing QUICK_ADD action, and exact `text/plain` SEND and PROCESS_TEXT filters; `QuickAddTileService` is exported only behind `BIND_QUICK_SETTINGS_TILE`; reminder/focus receivers are private, pending intents are immutable, and `FileProvider` is private and constrained | Every later exported surface requires a separate manifest, permission, input-validation, and cleanup review |
| T12 | Provider reads backup or attachment content | Backup and attachment objects are encrypted locally through the provider-independent authenticated codec; explicit authorization requests only `drive.appdata`; create-only transport has no update/PATCH path. A one-shot credentialed attachment gate proved exact-ID chunk create/occupied rejection, byte-identical readback, manifest create/readback/single lookup, and cleanup. | This is not broader live-provider or two-installation coverage. |
| T13 | Backup corruption, truncation, or incompatible format activates bad state | Strict bounded frames and payloads, checksum-before-AEAD, complete identity authentication, typed failures, staged full-vault verification, atomic activation, truthful transient-provider guidance, and genuine Activity recovery-route recreation evidence fail closed | A destructive live two-installation recovery was not claimed by this Stage 4 gate. |
| T14 | Stale writer overwrites a recovered lineage or mutates blob state | Writer epochs, conditional create-only control succession, ownership-loss handling, and explicit account-bound takeover are implemented | Additional live two-installation and prior-device reconnect coverage remains outside this qualification. |
| T15 | Missing/replaced control record recreates a known lineage | A client that observed control state treats absence/replacement as ownership loss and never recreates automatically; divergent work requires an explicit separate lineage | Broader live-provider coverage is not claimed. |
| T16 | Backup retention deletes the only recoverable base | Local and provider publication retain authenticated current/previous recoverable bases and bridging segments; promotion uses strict readback; lifecycle deletion is bounded and crash-resumable | Broader live provider tombstone/retention coverage is not claimed. |
| T17 | Attachment blob is deleted while live or retained recovery metadata references it | GC requires verified tombstone backup, current/previous-generation and 30-day eligibility, zero active/retained references, ownership reauthentication, and chunk-before-manifest deletion; destructive and terminal deletion clear bytes under the same bounded cleanup rules. | A purged record's blob set is not a GC candidate because v8 has no retired-set index; this conservatively leaks encrypted bytes until Stage 5. |
| T18 | Hostile attachment input exhausts disk or memory | Intake caps content at 100 MiB in 4 MiB chunks (at most 25), persists bounded sessions, verifies exact-ID readback, limits the ciphertext cache to `min(128 MiB, 5% available storage)`, and clears abandoned provisional/share files. | Interrupted intake expires after 24 hours; `resume()` has no product caller. |
| T19 | Missing or damaged attachment bytes corrupt structured work | Open authenticates the manifest, chunks, byte count, and aggregate hash; unavailable/corrupt bytes leave metadata visible with neutral unavailable state and never block task editing or invent content. | Attachment recovery remains bounded to the implemented lifecycle. |
| T20 | Dependency or CI compromise | Minimal repositories, read-only CI token, secret scanning/push protection, and reviewed Action commit pins | GitHub dependency maintenance remains paused; review every future Action revision before secrets exist |
| T21 | Plaintext export or notification discloses content | Contextual notification permission, private lock-screen content with generic public version, and opaque alarm IDs | Physical-device notification acceptance and separate export/widget/app-lock reviews remain required |
| T22 | Screenshots reveal unlocked content | No app-wide screenshot block, by design | Planned app-lock title privacy supplies user-controlled concealment |
| T23 | Saved UI state duplicates secrets outside SQLCipher | Saveable state is limited to bounded UI text, routes, filters, and record IDs; keys, passphrases, attachment bytes, and vault payloads are prohibited | Saved-instance state is not encrypted with the vault key; new sensitive input requires review |
| T24 | Malformed workflow, milestone, dependency, template, or time-entry data corrupts local state | Repository bounds, ownership checks, acyclic relation checks, atomic Room writes, exact Undo, and strict template/time-entry limits | Backup/import parsers must apply the same bounds before staging or activation |
| T25 | Portable restore activates beside an extant cloud writer | Restored packages are quarantined as inert input and Stage 2 exposes no activation action | If the cloud lineage is absent, Stage 3 portable recovery must activate under a new vault identity after warning; retaining identity requires successful takeover |
| T26 | A widget host, launcher preview, or app-drawer surface exposes a concealed task title | Glance title writes are gated by `titlesPermitted`, which is false whenever title privacy or lock concealment is engaged (exact predicate in the Stage 5 addendum below), enforced through the mutex-gated `StopGatedWriter` so no title write can land after a stop-time clear; title privacy is a dedicated always-on control independent of the lock feature; widget state sits outside the Android Auto Backup allow-list | Task titles remain plaintext at rest in the Glance state file until concealment engages; `locked` flips only on a qualifying foreground transition (cold start locked when the lock is enabled, or a background span at or beyond the chosen delay), not at the instant the lock toggle is turned on |
| T27 | The `.otvault` archive is stolen, intercepted, or its custody is compromised | The export passphrase is the same recovery passphrase; the archive envelope is a real recovery envelope (Argon2id 64 MiB, three iterations, parallelism one, random 16-byte salt), identical to the live vault's recovery envelope | Anyone who learns the export passphrase and obtains the archive gains the same access as a full recovery takeover; archive custody is equivalent to recovery-credential custody and must be protected with the same care as the passphrase itself |
| T28 | Plaintext CSV export discloses workspace content outside the vault, including formula injection against the opening spreadsheet application | A mandatory, undismissable-without-choice disclosure dialog explains the file is unencrypted before any write begins; cell values beginning `=`, `+`, `-`, or `@` are neutralised with a leading `'` | The exported file itself is permanently plaintext once created; this is a disclosed boundary-crossing control, not a confidentiality guarantee, and the exporting person is responsible for the file's custody thereafter |
| T29 | Another app sends private, oversized, or malformed text into Quick Add | Only `text/plain` SEND and PROCESS_TEXT are accepted; prefill uses the first trimmed line, caps it at 240 characters, keeps it as transient sheet state, and replaces an already-open prefill when a new intent arrives; no shared text is logged | The sending app and Android share UI can already read the source text; it becomes encrypted workspace content only after the person confirms Add |
| T30 | A third-party app invokes the Quick Settings capture surface | The exported tile requires the platform-only `BIND_QUICK_SETTINGS_TILE` permission and launches the existing lock-gated Quick Add route with no task-text extra | A compromised System UI is within the compromised-OS assumption; the tile deliberately reveals that Open Tasks is installed |
| T31 | A hostile CSV exhausts memory or corrupts existing records | Import caps input at 5 MiB and 5,000 rows, requires strict UTF-8 and the exact own-schema header, validates every row and relation before dispatch, previews create counts, creates only new records atomically, and returns exact Undo | CSV import deliberately duplicates matching records; it never attempts identity matching or third-party format recovery |
| T32 | Markdown export discloses project content outside the vault | Export is a user-selected, project-scoped SAF write; the product labels the format plain Markdown and deletes a partial document on every non-success path | The completed Markdown file is permanently plaintext and its custody belongs to the person who exported it |
| T33 | Hostile Quick Add text or a malformed saved-view payload causes unintended mutation or unbounded decode | Quick Add uses bounded pure parsing and confirm-only chips before repository validation; saved views cap names, text, count, and payload bytes, strictly decode only version 1 or 2, reject unknown keys and enums, and keep failed rows invisible | Quick Add deliberately recognizes a small grammar; a future payload version remains retained but unusable until a reviewed decoder exists |

## Cryptographic invariants

- Local database and vault-content keys are independent random 256-bit values.
- The vault-content key is independently wrapped for recovery and local
  per-vault Android Keystore access; neither envelope uses the SQLCipher key.
- A stored database or content-key envelope never causes creation of a new
  Keystore key.
- Recovery envelopes use format version 1 and reject weakened Argon2 metadata.
- Argon2id parameters are 65,536 KiB, three iterations, and parallelism one.
- AES-GCM uses 96-bit nonces and 128-bit authentication tags.
- Record associated-data encoding is covered by a golden vector.
- Cloud-object AEAD binds the complete canonical header identity and verifies
  frame length and ciphertext checksum before decryption.
- Changing a recovery passphrase re-wraps the existing content key.
- A database schema bump requires a non-destructive migration and exported
  Room schema. A cryptographic format bump requires old-format fixtures.
- Checksums detect corruption but never substitute for AEAD authentication.

## Dependency review

The 27 July 2026 review covered direct dependencies, Gradle repositories,
Android manifests, backup paths, logging calls, and the CI workflow.

| Dependency | Reviewed version | Decision |
|---|---:|---|
| Android Room | 2.8.4 | Retain; rehearse every schema migration on-device |
| SQLCipher for Android | 4.15.0 | Retain; require encrypted migration tests before any change |
| Google Tink Android | 1.23.0 | Retain; golden, associated-data, wrong-passphrase, cross-device, and tamper tests pass |
| Bouncy Castle `bcprov-jdk18on` | 1.84 | Retain for this gate; evaluate a later release separately with the Argon2 golden vector |
| AndroidX, Compose, Hilt, Kotlin, and AGP | Version catalogue | No security-motivated change identified; keep updates focused |

Dependabot checks Gradle and GitHub Actions weekly, but maintenance execution is
paused. Dependency changes must not be merged solely because a newer version
exists. Cryptography, database, and compiler upgrades require focused
compatibility tests and a fresh threat-model review.

## Stage 5 addendum

Stage 5 adds three platform-facing surfaces that cross the vault's normal
confidentiality boundary by design. Each is a disclosed, bounded exception
rather than a defect; see T26–T28 above for the corresponding gate rows.

**(a) Widget plaintext titles at rest.** The Glance Today widget's state
files hold up to three focus task titles in plaintext at rest, because
Glance state is a platform-managed file the widget host reads outside the
app process. Concealment is governed by one precise predicate:

```text
titlesPermitted = !(titlePrivacy || locked)
```

`titlePrivacy` is a dedicated, always-on widget-concealment setting,
independent of whether the lock feature is enabled at all — a person who
never enables app lock can still conceal widget titles. `locked` flips to
`true` only on a qualifying foreground transition: cold start is locked
when the lock is enabled, and an ordinary foreground resume locks only
once the prior background span is at or beyond the chosen delay
(Immediate, one, five, or fifteen minutes). Enabling the lock toggle does
**not** by itself flip `locked` or conceal titles; concealment takes
effect at the next qualifying transition, not at the moment of the
settings change. Every title write is gated through the mutex-gated
`StopGatedWriter`, so a stop-time title clear can never be raced by a
write already in flight, and widget state is excluded from Android Auto
Backup and device transfer along with every other non-portable-package
path.

**(b) Export passphrase equals recovery passphrase.** The `.otvault`
archive envelope is a real recovery envelope — Argon2id with 64 MiB
memory, three iterations, parallelism one, and a random 16-byte salt —
identical in kind to the live vault's own recovery envelope. There is no
separate, weaker "export password": the passphrase a person sets to
encrypt an archive is the same credential that later reconstructs the
vault's content key on import. Archive custody is therefore equivalent to
recovery-credential custody; anyone who obtains both the archive file and
its passphrase has the same access as a successful recovery takeover.

**(c) CSV export is disclosed plaintext.** CSV export is plaintext by
design — it exists so workspace data can leave the app for other tools —
and carries no encryption. A mandatory pre-export disclosure dialog states
this before any document write begins, with no "do not ask again" option.
Cell values that begin with `=`, `+`, `-`, or `@` are neutralised with a
leading `'` to block formula injection against the spreadsheet application
that later opens the file. This is a disclosed boundary-crossing control:
it protects the opening application from a hostile cell value, not the
exported content's confidentiality, which the disclosure already
surrenders by design.

## Stage 6 addendum

Stage 6 adds four bounded platform or plaintext boundaries; see T29–T32.

**(a) Share and text-selection intake.** SEND and PROCESS_TEXT accept only
`text/plain`. The prefill is the first trimmed line, capped at the task-title
limit, and remains transient until Add executes the ordinary encrypted
repository command. A second incoming intent replaces the current prefill
instead of appending or preserving stale shared content. Intent extras and
saved-search text are excluded from logs and telemetry.

**(b) Quick Settings capture.** The tile is not a general exported service.
Android alone can bind it through `BIND_QUICK_SETTINGS_TILE`; tapping it sends
only the existing boolean Quick Add route and still passes through app-lock
authority before content is composed.

**(c) Tasks CSV import.** Import is a bounded, create-only parser boundary:
5 MiB maximum source bytes, strict UTF-8, the exact Tasks export header, at
most 5,000 rows, validation before repository dispatch, and one atomic receipt
for Undo. Existing tasks, projects, and tags are never updated or merged. The
ViewModel releases and clears source bytes after parsing and clears the parsed
preview after completion, cancellation, or failure.

**(d) Markdown export is plaintext.** Project Markdown intentionally leaves
the encrypted vault through a person-selected SAF document. It contains the
selected project's summary, milestones, and tasks in readable text. This is an
interop feature rather than a confidentiality claim; completed-file custody
belongs to the person, while all partial-output failure paths delete the
document.

## Stage 7 implementation addendum

Stage 7 parses Quick Add and saved-view payloads as bounded untrusted input.
Grammar matches remain transient suggestions until individually confirmed;
ordinary repository validation still governs the resulting atomic command.
Saved-view decoding is version-first and strict for v1 and v2, with the existing
20-view, 64-character name, 500-character query, and 2 MiB payload bounds.

Sort and group persistence uses the private device-local `view_prefs` file. It
contains only enum names and project IDs, never task text, query text, vault
records, keys, or backup content, and preferences remain outside the exact-file
Android backup allow-list. Deleted-project entries are bounded identifier-only
residue rather than a cleanup path over vault data.

`DuplicateTask` is one validated repository mutation and deliberately excludes
the source reminder as well as recurrence, time entries, notes, attachments,
and prior activity. It therefore cannot arm a second alarm or enter the reminder
scheduler except through a later explicit edit. The Stage 7 changes add no
exported component, intent filter, runtime permission, Storage Access Framework
flow, provider scope, network request, or cloud object family. Room remains v9
and the authenticated backup object format remains v1.

## Stage 8 checkpoint addendum (in development, unqualified)

Tasks 1–9 add pure Month and Timeline projections, exact schedule validation,
atomic task/reminder mutation, start/due editor controls, the non-drag
rescheduling fallback, and a pointer drag layer that reuses the existing
schedule callbacks and remove confirmation. These changes add no external
input, permission, manifest component, network or Drive path, durable schema,
backup family, or plaintext boundary. Timeline UI/state and the daily digest
have not landed. No device or release qualification is claimed; Stage 7's
release waivers do not apply to Stage 8.

## Security acceptance gates

Before production release:

1. Retain independent verification of the authenticated provider-independent
   object codec and implemented bounded snapshot/operation decoders; add
   equivalent bounded decoders for every later portable/provider family before
   recovery consumes plaintext.
2. Prove app-managed backup verification, current/previous snapshot fallback,
   retention, corruption handling, and independent destructive actions.
3. Prove Android Auto Backup includes only the eligible portable package and
   excludes Room, keys, credentials, cache, and attachment bytes.
4. Exercise recovery after reinstall, new-device setup, and Keystore loss,
   including failed staging and rollback.
5. Prove conditional writer-epoch acquisition, stale-writer rejection, offline
   prior-device reconnect, and missing control-record handling.
6. Exercise hostile attachment intake, bounded transfer/cache, unavailable
   states, delete/Undo, retained references, and remote garbage collection.
7. Retain reviewed GitHub Actions pins and review every update before CI gains
   secrets.
8. Re-run every released database/crypto migration fixture, physical-device
   notification privacy, and separate export, widget, attachment, and app-lock
   privacy reviews.
9. Complete Privacy Policy, OAuth verification, Play Data Safety, signing, and
   store operations outside the repository.
