# Threat Model

Last reviewed: 16 August 2026

This document covers the implemented local-authority foundation and the
approved backup, recovery-takeover, and cloud-attachment programme. It is a
release gate: any new data flow, Android manifest component (exported or
not), cloud object, attachment path, notification channel, device-local
preference file, logging sink, or key format must update this model.

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
| Daily digest schedule setting | Device-local non-vault configuration | Private `daily_digest` preferences holding only `enabled`, `minute_of_day`, and `last_handled_epoch_day`; outside the exact-file Android backup allow-list |
| Daily digest notification content | Aggregate counts derived from private content | Counts-only private-visibility notification on its own `daily_digest` channel, with a generic public lock-screen version; never task titles |
| Per-project planning view state | Sensitive UI metadata | Android saved-instance-state bundle; presentation enum names, a Monday Timeline anchor date, and record IDs only |

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

AlarmManager ── DailyDigestReceiver ─── non-exported, no filter, action/data checked
    │ mark handled ─ re-arm next alarm ─ then read vault
    ▼
DailyDigestCoordinator ─ computeTodayProjection(titlesPermitted = false)
    └── counts-only private notification ── generic public version

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

The Stage 8 planning surfaces sit on both sides of that boundary without
widening it. Month, Timeline, and daily digest projections are pure
read-only functions over the active `WorkspaceSnapshot`: they derive
counts, spans, and dependency context and issue no command. The one new
write path is `SetTaskSchedule`, which updates a task's schedule and its
reminder in a single Room transaction and a single journal generation,
appending ordered task-then-reminder entries, so no partial schedule state
can be observed, journalled, or backed up. The daily digest is a consumer
of that boundary only; it never writes vault state.

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
| T34 | A rescheduling action leaves a task and its reminder inconsistent, or journals half a change | `SetTaskSchedule` validates the complete target state — identity, reminder-with-due, recurrence, start-before-due, and future-reminder rules — then writes the task schedule and its reminder in one transaction and one journal generation with ordered task-then-reminder entries; Undo is produced by the repository with the previous exact values, and both engines carry the same behaviour | Repository-produced Undo may restore a past reminder only through `restorePastReminder`, which bypasses solely the future-reminder check and never arms an already-past alarm; on-device alarm behaviour remains a Task 15 evidence item |
| T35 | A large or pathological dependency graph makes a planning projection unbounded | Timeline is a read-only projection over a fixed Monday-first 84-day window; transitive prerequisite and dependant traversal uses a visited set bounded by the snapshot's task count, spans clip to the window edges with a labelled continuation state, and out-of-window milestones contribute exact before/after counts instead of fabricated positions | Traversal deliberately crosses project boundaries and the chain summary counts unique out-of-project tasks rather than edges; a wide graph therefore costs one bounded snapshot walk, never unbounded work or an unbounded canvas |
| T36 | Corrupt, hostile, or foreign local digest preferences drive an unintended schedule | The `daily_digest` file accepts exactly `enabled` (Boolean), `minute_of_day` (Int) and `last_handled_epoch_day` (Long); raw types are validated before use, anything else fails closed to disabled at 08:00 retaining only a valid handled day, disabling writes only `enabled`, and an unusable schedule cancels the alarm rather than guessing one | The file is device-local non-vault state outside the exact-file Android backup allow-list and holds no task, project, query, count, zone id, or scheduled instant; retaining the handled day is what stops an off/on cycle duplicating a digest |
| T37 | Another application triggers, observes, or hijacks digest delivery | `DailyDigestReceiver` is declared `android:exported="false"` with no intent filter — the sole Stage 8 manifest delta — and still validates its action and data before acting; exactly one stable immutable broadcast `PendingIntent` is armed with `setAndAllowWhileIdle`, so arming replaces the previous alarm rather than accumulating and cancelling addresses the same alarm | No exact-alarm permission, foreground service, worker, exported component, or network path was added; a compromised System UI or OS stays within the compromised-OS assumption |
| T38 | Digest delivery discloses workspace content, or repeats after a clock change | Delivery is serialised under one mutex in the fixed order mark handled → re-arm the next alarm → read vault state → post, so a durable handled-day record and a re-armed alarm always precede any vault access; a handled local day is never posted again after a backward clock or date change; content comes from `computeTodayProjection(titlesPermitted = false)` and carries counts only, on a private-visibility channel with a generic public lock-screen version | Zero counts post nothing and leave tomorrow armed; missing vault state, permission denial, a disabled channel, or a notification `SecurityException` is handled for that day without retry. Physical-device channel, export-scope, and public/private evidence is recorded in `docs/qualification/stage8-planning-surfaces.md` |

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

## Stage 8 implementation addendum

Stage 8 is implemented through Task 14 and its fix wave, and whole-stage
reviewed with no Critical findings. It adds a pure Monday-first 42-cell
Month projection, exact single-task rescheduling with a drag layer over a
complete tap/menu fallback, a bounded project Timeline, saved per-project
planning state, and an opt-in private daily digest. See T34–T38 for the
corresponding gate rows. The digest receiver has landed; the earlier
checkpoint statement that the digest had not landed is superseded.

**(a) One atomic schedule mutation.** `SetTaskSchedule` is the single
command for explicit rescheduling in both repository engines. It validates
the complete target state before writing, updates the task schedule and its
reminder inside one transaction, advances the revision once, appends
ordered task-then-reminder backup-journal entries in the same generation,
and returns repository-produced Undo carrying the previous exact values. A
partially applied schedule, an orphaned reminder, and a half-written
journal generation are therefore all unreachable. The editor's debounced
save uses the start-aware `UpdateTask` instead, so the two paths never race
a single record. Drag rescheduling reuses these same callbacks: it adds no
command, arithmetic, or persistence state of its own, and the accessible
non-drag path remains complete.

**(b) Bounded planning projections.** Month and Timeline are pure
projections. Month renders a fixed 42-cell grid with six-dot density and
exact counts; Timeline renders a fixed 84-day Monday-first window, so no
project history can grow an unbounded canvas. Dependency highlighting walks
the non-binned snapshot graph transitively with a visited set bounded by
the snapshot's task count, even though command validation already prevents
cycles. Spans that leave the window clip to its edge with a labelled
continuation state, and dates outside it report labelled before/after
states and exact milestone counts rather than a fabricated in-window
position. Per-project presentation, the Monday-only Timeline anchor, and
the selected task live in `SavedStateHandle` as enum names, a date, and
record IDs; decoding is fail-closed and the legacy board boolean migrates
to the BOARD presentation.

**(c) Device-local digest preferences fail closed.** The `daily_digest`
preference file is device-local, non-vault, off by default, and holds
exactly three keys: `enabled` (Boolean), `minute_of_day` (Int in
`0..1439`), and `last_handled_epoch_day` (Long). Raw stored types are
validated before use; an unknown key, wrong type, or out-of-range value
rewrites the file closed to disabled at 08:00, retaining only a valid
handled day, and disabling writes only `enabled`. It stores no task,
project, query, count, zone id, notification payload, or scheduled instant,
and preferences remain outside the exact-file Android backup allow-list
alongside `view_prefs`. An unusable schedule cancels the alarm rather than
arming a guessed one.

**(d) The digest receiver is explicit and non-exported.**
`DailyDigestReceiver` is declared `android:exported="false"` with no intent
filter and is the sole Stage 8 manifest delta. It validates its action and
data before doing any work, so even an in-package mis-delivery is inert.
Exactly one stable immutable broadcast `PendingIntent` is armed through
`AlarmManager.setAndAllowWhileIdle`: a daily convenience does not justify
exact-alarm access, and one identity means arming replaces the previous
alarm while cancelling addresses precisely that alarm. Reconciliation on
foreground, boot, package replacement, and wall-clock or time-zone change
reuses the existing private system-event receiver. `OPEN_DAILY_DIGEST_HOME`
routes to Home through a consumed navigation signal, and the app-lock
overlay stays authoritative before any workspace composition.

**(e) Privacy ordering and counts-only content.** Delivery is serialised
under one mutex in a fixed order: mark today handled, re-arm the next
alarm, then read vault state, then post. Durable schedule bookkeeping
therefore always precedes vault access, so an interruption after the vault
read cannot lose the alarm, and a backward clock or date change cannot
re-post a handled day. Content is computed by a single
`computeTodayProjection(titlesPermitted = false)` call and carries counts
only; it is posted on the digest's own `daily_digest` private-visibility
channel with a generic public lock-screen version, so the channel can be
disabled independently of reminders. Zero counts post nothing. Missing
active vault state, permission denial, a disabled channel, and a
notification `SecurityException` are handled for that day without retry,
with the next alarm still armed.

**(f) No title, network, permission, or backup expansion.** No Stage 8
surface renders or transmits a task title outside the app process: the
digest is counts-only, the widget's existing `titlesPermitted` gate is
unchanged, and nothing new joins the Glance state file. Stage 8 adds no
network request, Drive scope, provider object, runtime or manifest
permission, Storage Access Framework flow, exported component, route, or
dependency. Room remains v9 and the authenticated backup object format
remains v1, with no new backup family, fixture, or exported schema, so the
Stage 8 planning and digest state never enters an encrypted backup object
or the portable package.

Stage 8 is versioned 1.2.0 (`versionCode = 3`). Device and release
qualification is recorded in `docs/qualification/stage8-planning-surfaces.md`
and `docs/qualification/release-1.2.0-sideload.md`, where remote CI and the
release tag remain pending. Stage 7's release waivers do not carry into
Stage 8.

## Implemented onboarding/dashboard/NFR addendum

The following repository controls are implemented. For release 1.4.0 the owner
accepted the recorded physical, provider, browser/accessibility, benchmark,
and API 37 emulator-system evidence boundaries. The rebuilt 1.4.0 candidate
passed its independent-owner signer check and is qualified for the approved
tag and push.

| ID | Threat | Implemented control | Residual |
|---|---|---|---|
| T39 | A fresh install performs provider work or implies Google is required | An idle no-vault launch creates a structural empty vault locally with no discovery/network side effect; Google and portable discovery begin only on the explicit Restore existing workspace action in More | Google Play services outside the process may perform platform work, but Open Tasks initiates no request before opt-in |
| T40 | Production fixtures disclose or corrupt a new person’s understanding of their data | Runtime code uses a production primary-workspace identity and structural seed only; tests inject fixtures explicitly; fresh-vault content counts are release-gated | Default workflow statuses are structural records required to create the first Inbox task |
| T41 | Exported dashboard content executes markup/script or loads remote resources | Typed bounded DTO, escaped embedded JSON, CSP, `textContent` rendering, no external URL/network API, hostile-content tests, 10 MiB cap | The file is intentionally plaintext and its recipient/custody is outside the vault after explicit disclosure |
| T42 | A dashboard leaks excessive workspace detail | Aggregate is default; detail is opt-in and allow-listed; descriptions, notes, activity bodies, attachment names/paths, account/provider data, recovery metadata and keys are always omitted | Project/tag labels and allowed task detail can still be sensitive and are covered by the plaintext disclosure |
| T43 | A stale notification or widget action mutates a locked vault | Concealed notifications omit actions; reminder and widget mutations carry revocable lock/title/generation predicates into the shared repository write boundary and recheck under the write mutex immediately before the transaction | Same-device access during a legitimately unlocked interval remains an intended capability; later mutation paths must use the same transaction-bound contract |
| T44 | Background delay, queued biometric success, or process death leaves passive content authorised | Backgrounding invalidates the prompt token, biometric success requires the current foreground generation, and reminder/widget sinks conceal immediately on background while the overlay retains its configured delay | Asynchronous cancellation/Glance rewriting required device proof; the owner-reported process-death sequence passed, and must be repeated if these sinks change |
| T45 | Hostile archive/CSV input exhausts memory/CPU or triggers spreadsheet formulas | 512 MiB archive aggregate cap before body allocation/write, per-frame cancellation, O(1) attachment map, indexed project/tag resolution, 500/1,000 creation caps, and CR/LF formula neutralisation | Legitimate input above a declared product ceiling is rejected rather than partially imported |
| T46 | Dependency or build-pipeline compromise reaches the signed app | Reviewed full-SHA Actions, CodeQL Java/Kotlin, high/critical dependency review, SHA-256 dependency verification, SBOM, checksum-pinned Gradle distribution in a versioned cache namespace, and a fail-closed verifier for all three APKs | The independent owner-input gate passed for the three exact current-head artifacts; every changed or rebuilt final candidate must repeat it, and the expected signer may never be derived from a candidate APK |
| T47 | A local edit made during an active remote backup is mistaken for the generation already attempted | The runner captures `currentGeneration` under the serialized publication gate and carries that immutable start generation plus execution sequence through completion; the runtime re-enqueues any newer generation | Future runner implementations must preserve the same capture boundary and retained completion state |

The HTML share cache is limited to `cache/share/reports/` under FileProvider,
granted read-only to the chosen recipient, and swept on the next report
operation. Every share uses a unique staging path while SAF retains the stable
download display name. Download and share delete partial output on
cancellation/failure.
No dashboard payload enters Android saved state, logs, CI artifacts, backup,
or Google Drive automatically.

Performance is also a security/availability property: active timers must not
drive one-second full snapshot rebuilds; search/Insights must cancel stale
work; archive/import/report memory is bounded; and physical-device benchmark
evidence must cover the 5,000-record ceiling without OOM or ≥700 ms frames.

Observed local evidence and each owner-accepted external boundary are recorded in
`docs/qualification/onboarding-dashboard-nfr-acceptance.md`.

The sealed Standard review
`df9a41d7-2458-4943-9c5d-957e98d484e9` found zero Critical/High, three
Medium, and two Low issues; all five implementation defects are remediated.
Closing scan `31e83519-4242-4240-9b61-7cb357b440e8` found one Medium and four
Low follow-up issues; those are also remediated. Post-fix scan
`19aa7c94-d7a0-4b6d-9cef-0e6c347e0ce5` reported zero findings with only the
now owner-passed device purge proof deferred, and exact backup follow-up scan
`2cb2540b-e277-43c5-a12f-564f386de9c8` reported zero findings with complete
coverage. Independent real three-APK signer proofs passed for both the exact
current-head 1.3.1 evidence set and the rebuilt 1.4.0 release set on 24 August;
repeating the gate for any later changed or rebuilt candidate remains a
release gate, not an open implementation defect.

Security runs `32615516366`, `32617307907`, and exact-head `32617911327`
passed CodeQL, with dependency review correctly skipped for each direct push.
Replacement Android run
`32615516358` proved that the bounded `--no-parallel` experiment did not
serialize UTP work before the API 37 emulator lost its activity/package
services and ran zero tests. `afb1d93` removes that ineffective flag; rollback
is not a security control or serialization proof. `c649801` independently
closes the APK-label boundary by requiring exact `aapt2 native-code` sets for
arm64, x86_64, and universal artifacts. The API 37 result remains an unresolved
canary availability boundary, not evidence of an application assertion
failure; no second speculative workaround is treated as a control.

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
   notification privacy — including the daily digest's own channel
   properties, its non-exported receiver scope, its delivery-intent
   identity, and its private/public content split — and separate export,
   widget, attachment, and app-lock privacy reviews.
9. Complete Privacy Policy, OAuth verification, Play Data Safety, signing, and
   store operations outside the repository.
10. Prove the automatic first launch initiates no provider/network work and
    creates zero user records, explicit recovery retains rollback and
    non-overwrite guarantees, and Google remains backup/recovery-only.
11. Exercise hostile aggregate/detail dashboard content offline, verify CSP
    and no external resources, prove the 10 MiB/partial-output boundaries, and
    inspect both SAF and FileProvider custody disclosures.
12. Prove locked stale reminder actions are inert, background lock expiry
    conceals widget state, active private notifications are cancelled, and
    maximum archive/CSV inputs meet their aggregate and creation caps.
13. Retain the completed remediation and post-fix scan evidence for
    `df9a41d7-2458-4943-9c5d-957e98d484e9`,
    `31e83519-4242-4240-9b61-7cb357b440e8`,
    `19aa7c94-d7a0-4b6d-9cef-0e6c347e0ce5`, and
    `2cb2540b-e277-43c5-a12f-564f386de9c8`; then require green
    CodeQL/applicable dependency review, Gradle wrapper and dependency
    checksum verification, the real owner-input signer check for every APK,
    SBOM inspection, per-ABI size caps, and fixed physical-device NFR evidence
    before the programme's release decision.
