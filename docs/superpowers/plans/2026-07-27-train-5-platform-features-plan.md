# Train 5 — Platform Features Implementation Plan

> **Replanning required — 28 July 2026:** The feature intent remains, but this
> train cannot execute until Stage 4 freezes the final local metadata and
> cloud-attachment contracts. Follow the live production master plan.

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete encrypted vault import/export, warned plaintext CSV export,
Today Glance, Quick Add refinement, app lock/title privacy, full keyboard and
mouse access, and explicit one-way calendar insertion.

**Architecture:** Archive codecs reuse the frozen cloud frames and final schema
7, importing into an isolated staged vault through the Train 3 activation
boundary. Platform components and launchers remain in `app`; feature modules
receive state and callbacks. Widget/title privacy read minimal app settings and
repository projections through the normal application boundary.

**Tech Stack:** Kotlin, cloud frame/Tink codecs, Room/SQLCipher schema 7,
Storage Access Framework, AndroidX Glance 1.2.0, AndroidX Biometric 1.1.0,
WorkManager, Activity intents, Navigation 3, Compose UI test, JUnit 4.

**Backlog:** P1-L08 and P2-F05 through P2-F07.

## Global Constraints

- Follow the master plan constraints.
- Do not change the v1 product schema beyond Room version 7.
- `.otvault` is encrypted and replacement-only; no partial merge policy.
- CSV is export-only, streamed, explicitly warned each time, and never staged.
- Widget, notifications, recents, and external Quick Add obey title privacy.
- App lock is an access gate; it does not replace SQLCipher/content-key
  encryption and cannot make recovery promises.
- Calendar insertion requests no calendar permission and stores no event ID.

---

### Task 5.1: Define the portable `.otvault` archive format

**Files:**
- Create:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/VaultArchiveFormat.kt`
- Create:
  `core/sync/src/test/kotlin/app/opentasks/core/sync/VaultArchiveFormatTest.kt`
- Create: `core/sync/src/test/resources/vault-archive/v1/*`

**Interfaces:**

```kotlin
data class VaultArchiveHeader(
    val magic: String = "OTVAULT",
    val archiveVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val manifestLength: Long,
    val objectCount: Int,
    val inventorySha256: String,
)

data class VaultArchiveInventoryEntry(
    val family: CloudObjectFamily,
    val objectId: String,
    val framedLength: Long,
    val framedSha256: String,
)

interface VaultArchiveCodec {
    fun write(
        header: VaultArchiveHeader,
        objects: Sequence<ArchiveObjectSource>,
        destination: OutputStream,
    )
    fun inspect(source: InputStream, totalLength: Long): VaultArchiveInventory
}
```

Outer bytes are a length-prefixed canonical header followed by the recovery
envelope, inventory, then the framed encrypted manifest/snapshot/segments and
attachment chunks. Inventory order is family then opaque object ID.

- [ ] Write failing golden tests for empty/minimal/attachment archives,
truncation, unknown fields, future minimum reader, negative/overflowing
length/count, duplicate object ID, invalid family order, inventory mismatch,
trailing bytes, and allocation-before-bound.

- [ ] Run:

```bash
./gradlew :core:sync:testDebugUnitTest \
  --tests '*VaultArchiveFormatTest' --stacktrace
```

Expected: compilation failure for the archive codec.

- [ ] Implement streaming read/write with the Train 1 bounds and a 10 GiB
maximum archive input. Do not buffer an object or full archive in memory.

- [ ] Re-run and byte-review the golden fixtures.

- [ ] Commit:

```bash
git add core/sync
git commit -m "feat: define portable encrypted vault archive"
```

### Task 5.2: Stream encrypted vault export

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/archive/VaultExportService.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/archive/VaultExportServiceTest.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/archive/VaultExportCoordinator.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/archive/VaultExportCoordinatorTest.kt`

**Interfaces:**

```kotlin
interface VaultExportService {
    suspend fun export(
        destination: OutputStream,
        recoveryPassphrase: CharArray,
    ): VaultExportResult
}
```

- [ ] Add failing tests for fresh passphrase, confirmed current passphrase,
snapshot/outbox/tombstone inclusion, all attachment chunks, empty vault,
destination failure, cancellation, key closure, inventory digest, and zero
plaintext temp files.

- [ ] Run focused tests and confirm missing implementation.

- [ ] Quiesce only snapshot enumeration, not normal edits for the full export.
Capture one consistent Room snapshot plus operation/attachment inventory,
rewrap the existing content key for the supplied passphrase, and stream framed
ciphertext directly to the SAF `OutputStream`.

- [ ] Close and zero passphrases/keys in `finally`. On destination failure call
the platform descriptor's truncate/delete path when supported and explain that
the user should remove any provider-created partial document.

- [ ] Re-run tests.

- [ ] Commit:

```bash
git add core/data/src/main/kotlin/app/opentasks/core/data/archive \
  core/data/src/test/kotlin/app/opentasks/core/data/archive \
  app/src/main/kotlin/app/opentasks/archive \
  app/src/test/kotlin/app/opentasks/archive
git commit -m "feat: stream encrypted vault exports"
```

### Task 5.3: Validate and atomically import `.otvault`

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/archive/VaultImportService.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/archive/ArchiveDomainValidator.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/archive/VaultImportServiceTest.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultImportInstrumentedTest.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/archive/VaultImportCoordinator.kt`

**Interfaces:**

```kotlin
data class VaultImportPreview(
    val taskCount: Int,
    val projectCount: Int,
    val noteCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long,
    val replacesCurrentVault: Boolean = true,
)

interface VaultImportService {
    suspend fun inspect(
        source: ReopenableInput,
        byteCount: Long,
        passphrase: CharArray,
    ): ImportInspection
    suspend fun activate(inspectionToken: ImportInspectionToken): ImportResult
    suspend fun cancel(inspectionToken: ImportInspectionToken)
}
```

The token is process-local, opaque, expiry-bounded, and references the staged
validated file set; it contains no key bytes.

- [ ] Add failing tests for round-trip, wrong passphrase, corrupt/tampered
object, oversized header/archive/collection, unsupported reader, duplicate IDs,
cross-vault references, invalid workflow/milestone/dependency/recurrence/zone,
missing attachment chunk, disk full, cancellation, process death, and active
vault unchanged on every rejection.

- [ ] Add device tests from a clean install and over a populated schema-7 vault
for inspect→preview→confirm→atomic activate→restart and rollback.

- [ ] Run tests and confirm missing service.

- [ ] Implement staged import:

```text
bound outer archive → unlock envelope → stream/authenticate every object
→ validate identifiers/limits/relations → build staged schema-7 SQLCipher DB
→ verify attachment inventory → open/canonical snapshot check
→ return preview/token → on confirmation use VaultFileSet atomic activation
```

Never mutate the active repository during inspection.

- [ ] Re-run unit/device tests.

- [ ] Commit:

```bash
git add core/data app/src/main/kotlin/app/opentasks/archive
git commit -m "feat: validate and activate encrypted vault imports"
```

### Task 5.4: Add disclosed, formula-safe CSV export

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/archive/CsvExporter.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/archive/CsvExporterTest.kt`
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/ExportScreen.kt`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/ExportScreenInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**

```kotlin
enum class CsvTable { TASKS, PROJECTS, TIME_ENTRIES }

interface CsvExporter {
    fun export(
        table: CsvTable,
        snapshot: WorkspaceSnapshot,
        destination: Writer,
        zoneId: ZoneId,
    )
}
```

- [ ] Write failing tests for fixed headers/order, CRLF, quotes/commas/newlines,
UTF-8, UK display dates, ISO machine dates, nulls, notes, and formula-leading
`=`, `+`, `-`, `@`, tab, and carriage return.

- [ ] Neutralise formula-capable cells by prefixing an apostrophe before RFC
4180 quoting. Stream rows; do not build the full CSV.

- [ ] Add an export screen that requires a fresh plaintext disclosure for each
selected table/destination. Use one SAF create-document flow per table and
retain no exported URI or plaintext copy.

- [ ] Run JVM/More/App tests.

- [ ] Commit:

```bash
git add core/data/src/main/kotlin/app/opentasks/core/data/archive/CsvExporter.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/archive/CsvExporterTest.kt \
  feature/more app/src/main
git commit -m "feat: add warned plaintext CSV export"
```

### Task 5.5: Build Today Glance with privacy-safe projection

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt`
- Create: `app/src/main/kotlin/app/opentasks/widget/TodayWidgetReceiver.kt`
- Create: `app/src/main/kotlin/app/opentasks/widget/TodayWidgetWorker.kt`
- Create: `app/src/main/kotlin/app/opentasks/widget/WidgetProjection.kt`
- Create: `app/src/test/kotlin/app/opentasks/widget/WidgetProjectionTest.kt`
- Create: `app/src/main/res/xml/today_widget_info.xml`
- Create: `app/src/main/res/drawable/today_widget_preview.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**

```kotlin
data class WidgetPrivacy(
    val locked: Boolean,
    val hideTitles: Boolean,
)

data class TodayWidgetState(
    val openTodayCount: Int,
    val overdueCount: Int,
    val focusLabels: List<String>,
    val titlesVisible: Boolean,
)
```

- [ ] Add failing projection tests for local date boundaries, overdue counts,
three-task cap, title privacy, app lock, empty state, and deleted/Bin tasks.

- [ ] Add Glance 1.2.0 dependencies. Implement a receiver that schedules a
normal application-initialised worker; it does not independently construct a
raw SQLCipher connection.

- [ ] Open-app and Quick Add actions contain only an action constant. They
carry no title/project text and enter the authenticated app flow.

- [ ] Add widget receiver tests and inspect its export/permission attributes.
Update on task/reminder/privacy changes, reboot, and periodic fallback.

- [ ] Run App tests and merged-manifest inspection.

- [ ] Commit:

```bash
git add gradle/libs.versions.toml app
git commit -m "feat: add privacy safe Today widget"
```

### Task 5.6: Add app lock, title privacy, and unified Quick Add

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/app/opentasks/privacy/AppPrivacySettings.kt`
- Create: `app/src/main/kotlin/app/opentasks/privacy/AppLockCoordinator.kt`
- Create: `app/src/main/kotlin/app/opentasks/privacy/AppLockScreen.kt`
- Create: `app/src/test/kotlin/app/opentasks/privacy/AppLockCoordinatorTest.kt`
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/PrivacySettingsScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/kotlin/app/opentasks/QuickAddSheet.kt`
- Modify: `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`
- Modify: `app/src/main/res/xml/shortcuts.xml`

**Interfaces:**

```kotlin
enum class LockDelay { IMMEDIATE, ONE_MINUTE, FIVE_MINUTES, FIFTEEN_MINUTES }

data class AppPrivacySettings(
    val lockEnabled: Boolean,
    val lockDelay: LockDelay,
    val hideTitles: Boolean,
    val blockScreenshots: Boolean,
)

interface AppLockCoordinator {
    val state: StateFlow<AppLockState>
    fun onBackgrounded(at: Instant)
    suspend fun requireUnlock(activity: FragmentActivity): UnlockResult
}
```

- [ ] Add failing tests for every delay boundary, wall/elapsed time handling,
process start locked, biometric success/failure/cancel/lockout, device
credential fallback, setting changes, widget/notification/recents title
privacy, and no saved authentication result.

- [ ] Add AndroidX Biometric 1.1.0. Use `BiometricPrompt` with
`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`; never handle biometric material.

- [ ] Use `Activity.setRecentsScreenshotEnabled(false)` for title-private
recents. Apply `FLAG_SECURE` only for the separate screenshot-blocking setting.
Set notification visibility/text and widget projection from the same privacy
settings.

- [ ] Route launcher shortcut, widget, notification, and in-app Quick Add
through one app-owned destination. Retain one bounded title, optional project,
keyboard submit, and no exported task text.

- [ ] Run unit, App process-restoration, More, notification, and widget tests.

- [ ] Commit:

```bash
git add gradle/libs.versions.toml app feature/more
git commit -m "feat: add app lock and title privacy"
```

### Task 5.7: Complete keyboard, mouse, hover, and non-drag actions

**Files:**
- Create:
  `app/src/main/kotlin/app/opentasks/KeyboardShortcuts.kt`
- Create: `app/src/test/kotlin/app/opentasks/KeyboardShortcutsTest.kt`
- Create:
  `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/InputModifiers.kt`
- Modify:
  `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt`
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/ShortcutHelpScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Modify:
  `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
- Modify:
  `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`

**Interfaces:** App-level resolver maps `KeyEvent` plus current route/transient
stack to `ShortcutAction`. Design-system modifiers provide visible focus and
hover without changing semantics.

- [ ] Add resolver tests for `Ctrl+K`, `/`, `Ctrl+N`, `Ctrl+Shift+N`, `?`,
`Esc`, text-field suppression, modifier variants, and topmost transient
dismissal.

- [ ] Add Compose tests for Enter/Space activation, visible focus/hover,
context-menu parity, explicit workflow/milestone/dependency/attachment/schedule
actions, and no drag-only operation.

- [ ] Implement shortcut priority:
focused text input consumes text; Esc dismisses top transient; route-specific
shortcut next; global shortcut last.

- [ ] Add a shortcut helper with platform labels. Preserve existing up/down
and explicit move actions as accessibility authority.

- [ ] Run App plus all feature device suites on compact and expanded profiles.

- [ ] Commit:

```bash
git add app core/designsystem feature
git commit -m "feat: complete keyboard and pointer access"
```

### Task 5.8: Add permission-free one-way calendar insertion

**Files:**
- Create:
  `app/src/main/kotlin/app/opentasks/calendar/CalendarInsertPlanner.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/calendar/CalendarInsertPlannerTest.kt`
- Modify:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Modify:
  `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**

```kotlin
data class CalendarInsertPreview(
    val title: String,
    val start: Instant?,
    val end: Instant?,
    val allDayDate: LocalDate?,
    val description: String,
)

interface CalendarInsertPlanner {
    fun preview(task: Task, project: Project?, zoneId: ZoneId):
        CalendarInsertPreview?
    fun intent(preview: CalendarInsertPreview): Intent
}
```

- [ ] Add failing tests for undated rejection, start+due, due-only all-day
context, DST, project description, no project, cancellation/no repository
mutation, and exact `ACTION_INSERT`/`Events.CONTENT_URI` extras.

- [ ] Add a preview/confirmation callback to stateless Task and Schedule
surfaces. Build and launch the intent in `app`; if no provider resolves it,
show a non-destructive error.

- [ ] Inspect merged manifest:

```bash
rg -n 'READ_CALENDAR|WRITE_CALENDAR' app/build/intermediates/merged_manifests
```

Expected: no output.

- [ ] Run tests and commit:

```bash
git add app feature/tasks feature/schedule
git commit -m "feat: add one way calendar insertion"
```

### Task 5.9: Qualify the complete platform feature set

**Files:**
- Create:
  `app/src/androidTest/kotlin/app/opentasks/PlatformFeaturesInstrumentedTest.kt`
- Modify: `docs/architecture.md`
- Modify: `docs/threat-model.md`
- Modify: `DESIGN.md`
- Modify: `PRODUCT.md`
- Modify: `README.md`
- Modify: `HANDOFF.md`

**Interfaces:** No production API change.

- [ ] Round-trip `.otvault` across a clean install with notes, tombstones,
templates, time entries, and attachments. Reject corrupt/oversized/future
fixtures while proving the active vault digest is unchanged.

- [ ] Exercise CSV disclosure/cleanup, widget reboot/process death, lock delay,
title privacy, external Quick Add, keyboard/mouse, and calendar cancellation on
API 36 compact and API 37 expanded devices.

- [ ] Verify no Room schema above 7 and no analytics/crash/ads/purchase,
calendar, or broad storage dependency/permission.

- [ ] Run train exit gates and all device suites. Update archive/privacy/platform
flows and evidence.

- [ ] Commit:

```bash
git add app/src/androidTest docs DESIGN.md PRODUCT.md README.md HANDOFF.md
git commit -m "test: qualify import export and platform features"
```
