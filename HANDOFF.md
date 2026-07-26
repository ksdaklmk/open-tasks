# Open Tasks Handoff

- Last updated: 27 July 2026
- Branch: `main`
- Pause point: P0 implementation and verification complete;
  dependency-blocked release gates are recorded below.

This is the only live project handoff and ordered backlog. Update it whenever
work changes scope, priority, dependencies, architecture, security assumptions
or verification status.

## Executive status

Open Tasks is a working local-first Android foundation, not yet a production
release. The encrypted local workspace, adaptive shell, task editor, recurring
tasks, workflows, Trash, project workbench, search and timers persist across
process restarts. The current P0 code hardening and acceptance slice is
complete and verified.

The public GitHub authority is
[ksdaklmk/open-tasks](https://github.com/ksdaklmk/open-tasks); local `main`
tracks `origin/main`. GitHub Actions runs the configured checks, while secret
scanning, push protection, Dependabot alerts and security updates are enabled.
Non-provider secret patterns and validity checks are unavailable in the
current repository plan and remain disabled. Google Identity, Drive transport,
cloud recovery and Play Console work have not started. P0 release gates which
depend on those features remain blocked by their listed prerequisites.

No task is intentionally left half-implemented at this pause point.

## Status vocabulary

| Status | Meaning |
|---|---|
| Done | Implemented and verified for its current scope |
| Ready | Can be started without another project task |
| Blocked | Cannot be completed until the named dependency or external input exists |
| Deferred | Deliberately ordered after higher-priority work |
| External | Requires an account, policy decision, physical-device session or store operation |

## Completed product foundation

### Application and adaptive UI

- Single-activity Kotlin/Compose app with Navigation 3, Hilt and Material 3
  Adaptive.
- Five destinations: Home, Tasks, Projects, Schedule and More.
- Compact navigation bar and medium/expanded navigation rail.
- One-pane and list/detail task and project workbenches selected by
  `WorkspaceLayoutPolicy`, including separating-fold handling.
- Responsive task editor now uses the available detail-pane width rather than
  the whole device width. Narrow panes stack Planning content, and option
  groups wrap instead of being clipped.
- Navigation labels stay readable at 100% and 130% text and deliberately
  collapse before wrapping at 150% and 200%.
- Final visual acceptance passed on the API 37 Pixel 10 Pro Fold main display
  at normal density and text scale. Earlier checks covered the compact cover
  display, fold/unfold, narrow detail panes and 200% text.

### Local workspace

- Encrypted task CRUD, core-field editing, debounced auto-save and exact Undo.
- Granular checklist and reusable tag editing with relation-safe Undo.
- Persisted first-class workflow statuses with semantic completion behaviour
  and blocked-completion acknowledgement.
- Thirty-day Trash, restore, startup expiry, permanent delete and sync
  tombstones.
- Adaptive Project Workbench with create, edit, archive, restore, progress,
  workflow counts, milestone context and deep links.
- Universal search across the implemented local records.
- Persisted timer and time-entry foundation.
- One-time sample workspace seed followed by Room as the sole local authority.

### Recurring tasks

- Daily, weekly, monthly and yearly frequencies.
- Intervals, multiple weekly weekdays, count limits and end dates.
- Stable series ID, original wall-clock anchor and occurrence index.
- DST-safe wall-clock scheduling, non-drifting month-end scheduling and
  deterministic occurrence IDs.
- Completion and next-occurrence creation are one Room transaction with
  separate outbox operations.
- Repeated completion or redelivery creates exactly one next occurrence.
- Completion Undo reopens the original and removes only the generated
  occurrence.
- Editing a generated occurrence and undoing that edit restores its exact
  recurrence rule, due time, series ID, anchor and occurrence index.
- Room v1→v2 migration is non-destructive and preserves encrypted data.
- On-device Compose coverage exercises every cadence, interval editing,
  multiple weekdays, count ending, 200% text, 48 dp targets and keyboard
  activation.

### Data, cryptography and sync foundations

- Every write is a typed `DomainCommand` executed by `VaultRepository`.
- Room writes and outbox operations are atomic.
- SQLCipher database with a random 256-bit key wrapped by a non-exportable,
  unlocked-device-required Android Keystore AES-GCM key.
- Existing local envelopes fail closed if their Keystore key is lost; a new
  key is never silently substituted.
- Tink AES-256-GCM record encryption and Argon2id recovery envelopes.
- Golden vectors cover Argon2id output and associated-data encoding.
- Tests cover wrong passphrase, weakened KDF metadata, ciphertext/envelope
  tamper, associated-data swapping, passphrase change, key zeroisation and
  second-device decrypt.
- Hybrid logical clock and deterministic scalar, set and tombstone merge
  primitives cover clock rollback, redelivery, idempotence and arrival order.
- Repository shutdown now cancels and joins the Room observation job before
  its owner closes SQLCipher. This fixes a real connection-pool race found by
  the restart suite.

### Security and maintenance

- Threat model, asset inventory, trust boundaries, dependency review,
  residual risks and release gates are in
  [docs/threat-model.md](docs/threat-model.md).
- Architecture lifecycle and security references are aligned in
  [docs/architecture.md](docs/architecture.md).
- Android backup and device-transfer rules exclude vault data and keys.
- Current source scan found no application logging calls or committed secrets.
- Dependabot is configured weekly for Gradle and GitHub Actions.
- Public-repository audit found no credential, private-key, OAuth-client or
  provider-token patterns in the tracked tree or its existing history.
- `.gitignore` excludes local properties, environment files, signing keys,
  OAuth/service-account files, local vault databases/exports and generated
  release artefacts.
- A useful repository rollback point now exists:
  `806090a Establish Open Tasks baseline`.

## Work completed in this P0 pass

| ID | Result | Evidence |
|---|---|---|
| P0-01 | Established a real baseline commit after auditing ignored files and scanning for secrets | Commit `806090a` |
| P0-02 | Re-audited the codebase and reconciled the stale recurrence handoff with the implementation | This handoff, architecture and threat-model updates |
| P0-03 | Completed the recurrence rule matrix and edge-case acceptance | Unit tests for all cadences, intervals, weekdays, count/end date, month-end and DST; nine Tasks device tests |
| P0-04 | Hardened duplicate completion, restart/redelivery and exact Undo | In-memory and encrypted Room regression tests |
| P0-05 | Added current-slice accessibility acceptance | 200% text, 48 dp/click semantics and keyboard focus/Enter activation on-device; narrow/fold visual checks |
| P0-06 | Added API 36/37 instrumented CI jobs | `.github/workflows/android.yml`; YAML parsed locally |
| P0-07 | Completed threat and direct-dependency review | `docs/threat-model.md`; weekly Dependabot configuration |
| P0-08 | Added crypto golden, tamper, wrong-passphrase and key-loss coverage | Core crypto unit suite and Android Keystore device suite |
| P0-09 | Rehearsed every released Room migration | Only v1→v2 exists; encrypted migration device test passes |
| P0-10 | Strengthened multi-device foundations | Second-device recovery test plus merge/HLC rollback, retry and order tests |
| P0-11 | Fixed repository teardown ordering | All 12 encrypted Room/Keystore device tests pass without connection-pool crashes |
| P0-12 | Verified R8/resource shrinking and installed final debug build in place | Release assembly passes; app data retained after cold restart |
| P0-13 | Published the audited history as a public GitHub repository | `main` tracks `origin/main`; GitHub secret scanning and push protection enabled |

## Final verification record

The final source state passed:

```bash
./gradlew :app:assembleRelease --stacktrace
./gradlew testDebugUnitTest lintDebug --stacktrace
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest --stacktrace
./gradlew :app:installDebug
```

Results:

- All debug unit tests passed.
- Lint passed with zero errors and 20 non-blocking warnings: version update
  notices, one obsolete `mipmap-anydpi-v26` folder and one existing modifier
  parameter-order warning.
- R8 minification, resource shrinking and release APK assembly passed.
- All 28 device tests passed on the API 37 Pixel 10 Pro Fold emulator:
  12 data/Room/Keystore, 9 Tasks, 5 Projects and 2 More.
- Debug APK installed over the existing app; no uninstall or data clear was
  performed.
- Cold restart succeeded and the UI hierarchy confirmed persisted workspace
  content, including `Persistence check edited`.

Operational note: do not combine `lintDebug` and `assembleRelease` in the same
parallel Gradle invocation. AGP 9.3.1 lint can race KSP while release Hilt
sources are replaced, producing a transient missing
`Hilt_MainActivity.java`. Running release assembly and unit/lint as the two
phases above is stable. This is a tooling race, not a lint finding.

## Current in-progress work

None. Work is paused at the requested P0 boundary. The working tree should be
committed as one P0 checkpoint after this handoff is reviewed.

## Remaining tasks ordered by priority and dependency

The order within each table is topological: start with the lowest-numbered
ready item whose dependencies are satisfied. Release-gate P0 items remain P0
even when their implementation is blocked by P1/P2 product work.

### P0 — release and acceptance gates

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P0-R02 | External | Direct TalkBack, Switch Access, high-contrast, reduced-motion and RTL acceptance on the currently implemented surfaces | Human/physical-device session |
| 2 | P0-R03 | Blocked | End-to-end multi-device conflict, authentication expiry, quota, pagination, corruption, retry, reinstall and new-device recovery tests | P1-D01 through P1-D06 |
| 3 | P0-R04 | Blocked | Final full-app accessibility audit at 100/130/200% text, keyboard, TalkBack, Switch Access, high contrast, reduced motion, RTL and compact/expanded layouts | All P1/P2 UI surfaces complete |
| 4 | P0-R05 | Blocked | Screenshot/responsive regression suite for API 36/37, Fold cover/main, tablet, rotation, split-screen and live resizing | Stable P1/P2 UI plus screenshot harness |
| 5 | P0-R06 | Blocked | Baseline Profile and Macrobenchmark module; validate startup, scrolling and large task sets | Stable critical journeys plus large-data fixture |
| 6 | P0-R07 | Blocked | Final R8/resource-shrinking and performance budgets against production feature set | P0-R05, P0-R06 and all release features |
| 7 | P0-R08 | Ready before secrets | Pin GitHub Actions to reviewed commit SHAs | Complete before CI receives signing or OAuth secrets |
| 8 | P0-R09 | Blocked | Complete recovery UX for Keystore loss, reinstall and new devices | P1-D04 cloud migration/recovery |
| 9 | P0-R10 | External | Privacy Policy, OAuth brand verification, Play Data Safety, Play App Signing and internal/closed/open/staged rollout | P1 Drive slice, all product features, owner accounts and policy decisions |

### P1 — local core workspace

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P1-L01 | Ready | Reminders and notification actions, permission timing and exact-alarm fallback | Existing task/due model |
| 2 | P1-L02 | Ready | Custom per-project workflows: rename, reorder, add and archive while preserving semantic reporting categories | Existing workflow records and commands |
| 3 | P1-L03 | Ready | Milestone editing and task milestone membership | Existing milestone schema/project workbench |
| 4 | P1-L04 | Ready | Dependency editor, cycle-rejection UI and blocked-completion warnings at every task entry point | Existing dependency rules |
| 5 | P1-L05 | Ready | Full-text search for notes and attachment names | P2-F02 note/attachment records |
| 6 | P1-L06 | Ready | Schedule compact day agenda, expanded week timeline and unscheduled-task tray | Existing due dates; P1-L01 for reminder affordances |
| 7 | P1-L07 | Ready | Complete process restoration for selection, drafts, scroll, filters and timer state | Current SavedStateHandle/navigation foundation |
| 8 | P1-L08 | Deferred | Encrypted `.otvault` import/export and deliberate plaintext CSV warnings | Threat-model parser gates; final local schemas |

### P1 — Drive-primary storage and recovery

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P1-D01 | Ready with credentials | Google Identity authorisation using only `drive.appdata` | OAuth client/account configuration |
| 2 | P1-D02 | Ready | Versioned encrypted manifest, snapshots, per-device operation segments and checksum/bounded-decoder rules | Existing `core:crypto`, `core:sync`, threat model |
| 3 | P1-D03 | Blocked | Drive `CloudObjectStore`, `changes.list`, pagination and resumable object transport | P1-D01, P1-D02 |
| 4 | P1-D04 | Blocked | Outbox upload, remote download, idempotent merge, retry/backoff and visible sync health | P1-D03 |
| 5 | P1-D05 | Blocked | Local-to-Drive migration with checksum verification and a seven-day local rollback copy | P1-D04 |
| 6 | P1-D06 | Blocked | Drive-to-local disconnect without cloud deletion; guarded cloud-delete recovery | P1-D04, P1-D05 |
| 7 | P1-D07 | Blocked | Resumable encrypted attachment upload and offline attachment cache policy | P1-D02 through P1-D04; P2-F02 |
| 8 | P1-D08 | Blocked | Complete the cloud/multi-device P0 test matrix | P1-D01 through P1-D07 |

### P2 — productivity and full-workspace features

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P2-F01 | Deferred | Templates with relative dates, workflows, milestones and task structure | P1-L02, P1-L03 |
| 2 | P2-F02 | Deferred | Notes/activity history and attachments using Photo Picker, Storage Access Framework, Sharesheet, drag/drop, constrained `FileProvider` cleanup and 100 MB limits | Local attachment encryption/design; P1-D07 for cloud |
| 3 | P2-F03 | Deferred | Manual time entries and timer-overlap reconciliation | Existing timer foundation |
| 4 | P2-F04 | Deferred | Insights for completion, overdue work, estimate/actual time, project/tag time and milestone health, with table/text alternatives | P1-L03, P2-F03 |
| 5 | P2-F05 | Deferred | Today Glance widget, Quick Add launcher refinement and app-lock title privacy | P1-L01; privacy review |
| 6 | P2-F06 | Deferred | Keyboard shortcut helper, mouse/hover support and accessible alternatives to drag actions | Stable final navigation/editors |
| 7 | P2-F07 | Deferred | One-way calendar export through `ACTION_INSERT` | P1-L06; export/privacy review |

### P3 — repository and developer experience

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P3-T01 | Ready | Restart Claude Code or open `/hooks` once to load `.claude/settings.json`; confirm the raw-colour hook fires through the harness | Local developer action |
| 2 | P3-T02 | Ready | Decide whether ktlint or Spotless is worth the maintenance cost; current authority is official Kotlin IDE formatting | Team preference |
| 3 | P3-T03 | Optional | Evaluate optional Compose/frontend and skill-authoring plugins only if a concrete workflow needs them | None |

## Architecture and security rules for the next agent

- Read [docs/architecture.md](docs/architecture.md),
  [docs/threat-model.md](docs/threat-model.md), [DESIGN.md](DESIGN.md) and
  [PRODUCT.md](PRODUCT.md) before changing the corresponding contract.
- Every write must remain a `DomainCommand` through `VaultRepository`.
- Mutations and their outbox operations must remain one transaction.
- Undo is repository-produced; never reconstruct it in UI code.
- Keep `InMemoryVaultRepository` behaviour aligned with
  `RoomVaultRepository`.
- Close `RoomVaultRepository` before closing its `VaultDatabase`; repository
  close joins the observation job.
- A Room version bump requires an exported schema and non-destructive
  migration fixture.
- A crypto-format bump requires old-format fixtures and golden-vector review.
- Never replace a missing Keystore key for an existing local envelope.
- Keep passphrases as `CharArray` and zero temporary key arrays.
- Never log private content, account data, Drive IDs, attachment names or
  encryption metadata.
- Future Drive code receives encrypted objects only and requests only
  `drive.appdata`.
- Layout decisions use `WorkspaceLayoutPolicy`, never a device model.
- Feature composables stay stateless and free of Hilt.
- Update architecture, design, threat-model and handoff documents in the same
  change whenever their contracts are affected.

## Recommended next action

Start with `P1-L01` unless Drive credentials and cloud work are the immediate
product priority. If Drive is chosen, implement `P1-D01` and `P1-D02` as
separate reviewable changes, then join them at `P1-D03`. Do not attempt the
blocked P0 cloud/release gates before their product dependencies exist.
