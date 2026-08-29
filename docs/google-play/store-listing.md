# Google Play store listing and declaration evidence

Versioned source of truth for the first Open Tasks Google Play submission. The
default locale is English (United Kingdom). Console answers must be reconciled
with the current question wording and the exact final AAB before submission.

## Fixed listing identity

| Field | Value | Status |
|---|---|---|
| App name | Open Tasks | Fixed |
| Package | `app.opentasks` | Fixed |
| Category | Productivity | Fixed |
| Price | Free | Fixed |
| Ads | No ads | Fixed |
| Default language | English (United Kingdom) | Fixed |
| Target audience | 13 and older; not directed to children or Families | Fixed |
| Availability | Every Play-supported country and region | Fixed |
| Privacy policy | `https://ksdaklmk.github.io/open-tasks/privacy/` | Current public URL PASS; DNS-verifiable custom-domain replacement PENDING before submission |
| Support | `https://ksdaklmk.github.io/open-tasks/support/` | Current public URL PASS; DNS-verifiable custom-domain replacement PENDING before submission |
| Open Tasks account | None; the local workspace requires no account | Fixed from shipped flow |
| Developer public display identity | Kritsada K. | PASS — verified public Play identity; private legal/contact evidence remains owner-only |

## Task 8 OAuth pause checkpoint

| Surface | Current evidence | Status |
|---|---|---|
| Audience and publishing | External; In production | PASS — current Google Auth Platform state |
| Requested scope | `https://www.googleapis.com/auth/drive.appdata` only; 17 stale scopes removed after current/released/history source audit | PASS — current source and Cloud configuration; PENDING — final AAB audit |
| Android OAuth delivery identity | Two existing clients for `app.opentasks` are preserved: one Play-delivery/release client and one older direct/debug client; upload certificate excluded | PASS — current Cloud and signing evidence; no client ID recorded |
| Branding fields | Open Tasks, current public privacy/support URLs, and existing private contacts are saved; no logo was added | PARTIAL PASS — branding is not yet shown to users |
| Search Console | Parent project URL and exact support homepage URL-prefix properties are verified through the deployed public HTML file | PASS — URL-prefix ownership only; insufficient for OAuth brand verification |
| OAuth brand verification | Current guidance requires a DNS-verified Search Console Domain property; `github.io` DNS is not owner-controlled | BLOCKED — owner must supply and approve a custom domain/subdomain with DNS control |
| Outside-allowlist consent | No ordinary Google account outside the former Testing allowlist was used | PENDING — configuration-only consent-flow evidence |

The custom-domain migration is not Console-only. Before resuming, amend and
approve Task 8 to update the hard-coded in-app privacy URL, both static-page
cross-links, public deployment checks, this listing, the qualification ledger,
and the OAuth homepage/privacy/authorised-domain values together. Keep the
current Pages URLs and `site/googlebfb12df764b54328.html` available until the
replacement domain and OAuth branding are both verified.

## Exact en-GB listing copy

### App name

Open Tasks

### Short description

Private, local-first tasks and projects with optional encrypted backup

### Full description

Plan tasks, projects and your day without creating an account.

Open Tasks keeps your workspace in an encrypted vault on your device. Capture tasks quickly, organise projects and tags, plan dates and reminders, use focus timers, and review work from phone or large-screen layouts.

Your workspace works locally by default. When you choose, you can create encrypted backups in your private Google Drive app-data storage, prepare an Android backup package, or export an encrypted Open Tasks vault. Import tools are available from More.

Open Tasks has no ads, analytics or in-app purchases.

### Release notes

First Google Play release. Open Tasks starts directly in a private local workspace, with import and encrypted backup and recovery available from More.

### Mechanical limits

The literal fields above were measured with `printf %s | wc -m` on 2026-08-27.

| Field | Characters | Play limit | Outcome |
|---|---:|---:|---|
| Short description | 70 | 80 | PASS |
| Full description | 588 | 4,000 | PASS |
| Release notes | 150 | 500 | PASS |

The copy uses en-GB `organise`, requires no Open Tasks account, and makes no
claim of live sync, collaboration, multi-user sharing, Google affiliation,
absolute security, a desktop/web app, or broad Android compatibility.

## Data Safety evidence

This is a field-by-field audit, not a declaration that no data is collected.
Source establishes the paths below; Play classification and the Console answer
remain pending until the exact AAB and current form wording are available.

| Data type | source path | on-device processing | leaves device | recipient/path | Play collection classification | Play sharing classification | required or optional | purpose | encrypted in transit | deletion path | final AAB evidence | Console answer |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Task titles and bodies | `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`; backup/export codecs; `calendar/CalendarInsertion.kt` | Stored in the encrypted local vault; used for task views, search and planning | Enabled encrypted backup/package/archive, explicit plaintext CSV/Markdown/report export, or confirmed calendar handoff; the calendar handoff sends the task title only and does not send the task body | Google Drive app-data; Android backup/transfer; user-selected document provider/app; selected calendar provider/app | Local-only by default; optional off-device paths, including the calendar-provider handoff, require current Play classification | No developer-directed sharing shown; provider/user-directed exceptions, including calendar handoff, require current Play wording | Local processing required for the feature; every off-device path optional | App functionality; optional backup/recovery, user export or calendar-event drafting | Drive uses HTTPS and encrypted objects; Android/provider transport varies; calendar-provider transport is destination-controlled | Delete in app or clear app data/uninstall; delete remote history for Drive; user deletes exported files; calendar entry deletion is controlled by the user/provider | PENDING — final AAB audit | PENDING — final AAB audit |
| Notes | `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`; `WorkspaceCsvWriter.kt`; backup codecs | Stored in the encrypted local vault and rendered locally | Same optional encrypted backup paths; explicit CSV export is plaintext | Same backup recipients; user-selected CSV destination | Same source position as task content | Same source position as task content | Required locally when used; off-device paths optional | App functionality; backup/recovery; user export | Same path-dependent result as task content | Same local/Drive/export deletion paths as task content | PENDING — final AAB audit | PENDING — final AAB audit |
| Projects | `Entities.kt`; `WorkspaceCsvWriter.kt`; `ProjectMarkdownWriter.kt`; backup codecs; `calendar/CalendarInsertion.kt` | Stored and organised in the encrypted local vault | Optional encrypted backup/archive/package, plaintext export, or project name in a confirmed calendar handoff | Same backup recipients; user-selected document destination; selected calendar provider/app | Same source position as task content; calendar handoff requires current Play classification | Same source position as task content; calendar handoff requires current Play wording | Required locally when used; off-device paths optional | Project organisation; backup/recovery; user export or calendar-event context | Same path-dependent result as task content; calendar-provider transport is destination-controlled | Same local/Drive/export deletion paths; calendar entry deletion is controlled by the user/provider | PENDING — final AAB audit | PENDING — final AAB audit |
| Tags | `Entities.kt`; task/project backup and export projections | Stored and applied in the encrypted local vault | Optional encrypted backup/archive/package; included in user-directed plaintext exports where selected | Same backup recipients; user-selected document destination | Same source position as task content | Same source position as task content | Required locally when used; off-device paths optional | Organisation; backup/recovery; user export | Same path-dependent result as task content | Same local/Drive/export deletion paths as task content | PENDING — final AAB audit | PENDING — final AAB audit |
| Schedules and dates | task/project entities; `feature/schedule`; backup/export codecs; `calendar/CalendarInsertion.kt` | Dates and planning state are processed locally in the vault and UI | Optional encrypted backup/archive/package, plaintext export, or confirmed calendar handoff of start/due dates | Same backup recipients; user-selected document destination; selected calendar provider/app | Same source position as task content; calendar handoff requires current Play classification | Same source position as task content; calendar handoff requires current Play wording | Required locally when used; off-device paths optional | Scheduling; backup/recovery; user export or calendar-event drafting | Same path-dependent result as task content; calendar-provider transport is destination-controlled | Same local/Drive/export deletion paths; calendar entry deletion is controlled by the user/provider | PENDING — final AAB audit | PENDING — final AAB audit |
| Reminders | `ReminderEntity`; `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`; backup codecs | Stored locally, scheduled through Android alarms and displayed in local notifications | Optional encrypted backup/archive/package; notification content is delivered by Android on the device | Google Drive app-data or Android backup only when the user enables those paths; local Android system otherwise | Local notification processing is on-device; backup classification remains pending | No developer-directed sharing shown; lock/title-privacy settings can suppress external title display | Reminder feature optional; backup optional | Reminder delivery; backup/recovery | Drive HTTPS/encrypted objects; Android backup transport pending | Delete reminder/task; clear app data/uninstall; delete remote history for Drive | PENDING — final AAB audit | PENDING — final AAB audit |
| Timers and time entries | `TimeEntryEntity`; `app/src/main/kotlin/app/opentasks/focus`; `WorkspaceCsvWriter.kt`; backup codecs | Focus timers and recorded time are processed locally | Optional encrypted backup/archive/package; explicit time-entry CSV export is plaintext | Same backup recipients; user-selected CSV destination | Same source position as task content | Same source position as task content | Timer use optional; backup/export optional | Focus timing, records, backup/recovery and export | Same path-dependent result as task content | Delete records/clear app data; delete Drive history; user deletes exported CSV | PENDING — final AAB audit | PENDING — final AAB audit |
| Attachments and imported files | `AttachmentEntity`; attachment intake/cache; `OtVaultExporter.kt`; remote attachment backup | Imported bytes are copied, encrypted/chunked and cached locally; selected CSV/`.otvault` files are read locally | Drive and `.otvault` can carry encrypted attachment file bytes; the Android portable package carries only the encrypted attachment record/metadata, not attachment file bytes; an explicit share grants a cached FileProvider URI to a chosen app | Google Drive app-data; Android backup/transfer; user-selected document provider/app | Optional user content transfer; exact exception/collection labels remain pending | No automatic third-party sharing shown; explicit share/export is user-directed | Entirely optional | Attachments, import, backup/recovery and explicit sharing | Drive uses HTTPS and encrypted chunks; `.otvault` is encrypted; chosen provider/app transport varies | Delete attachment content leaves attachment records and other backup history; delete backup history leaves a non-secret terminal safety marker and does not delete the separate Android package; user deletes destination file | PENDING — final AAB audit | PENDING — final AAB audit |
| Settings | local settings stores including `AppLockSettings.kt`, `ViewArrangementStore.kt` and reminder preferences | Preferences are read and applied locally | Ordinary preferences are excluded from Android backup; no app-source network path was found for them, while exact vault-record coverage remains pending | No recipient established for ordinary preferences; optional encrypted backup recipient only if a setting is part of captured vault records | Predominantly on-device; exact captured fields require final artifact/runtime audit | No developer-directed sharing shown | Required only to remember chosen settings; any backup optional | App customisation; backup/recovery only where captured | N/A for ordinary local preferences; Drive HTTPS/encrypted objects only if included | Reset settings or clear app data/uninstall; delete Drive history if a captured setting is included | PENDING — final AAB audit | PENDING — final AAB audit |
| Backup metadata | `RemoteBackupEntities.kt`; Drive metadata codecs; `HttpCreateOnlyDriveTransport.kt` | Encrypted local database retains configuration, per-install account digest, lineage/object IDs, provider IDs and resumable state | Drive receives file names, roles and bounded app properties in app-data metadata; encrypted payload objects also leave | Private Google Drive app-data over Drive API | Optional backup operational data; exact Play label pending | Google service-provider path, not advertising/social sharing; exact label pending | Optional, created only after backup is enabled | Backup, recovery, retention and safe deletion | HTTPS to `https://www.googleapis.com`; payload objects authenticated/encrypted, while required Drive file metadata is not claimed to be payload-encrypted | More → Backup & recovery → delete history/content; attachment-content deletion leaves attachment records/other history; history deletion leaves a non-secret terminal safety marker and not the Android package | PENDING — final AAB audit | PENDING — final AAB audit |
| Optional Google account identifier | `DriveAccountBinding.kt`; `GoogleDriveAuthorizationManager.kt`; `RemoteBackupConfigEntity` | Drive permission ID is converted to a per-install HMAC-SHA-256 digest; the persisted configuration/digest remains after disconnect for same-account reconnection; token/account handles are session-only in app source | Google authorization and Drive `about.get` exchange account/grant data; no raw email/profile/permission ID is persisted or sent to a developer backend by app source | Google Play Services authorization and Google Drive API | Optional account-management/identifier processing; exact SDK behavior and Play label pending | Google service-provider authorization path; no app-source sale/ad sharing | Optional; only for Drive backup/recovery | Authorization, account binding, backup and recovery | Play Services transport requires final audit; Drive API call is HTTPS | Disconnect stops work, marks configuration `DORMANT` and attempts grant revocation; clear app data/uninstall removes the local digest/configuration | PENDING — final AAB audit | PENDING — final AAB audit |
| Diagnostics | No embedded diagnostics client found in source/dependency scan; Google Play may separately supply platform vitals/diagnostics | Application source does not record or upload diagnostics | No application-code path found; Play-delivery diagnostics are platform behavior outside an embedded app SDK | Google Play platform, if enabled by Play | Must distinguish Play platform diagnostics from app collection | Must distinguish Play platform diagnostics from embedded SDK sharing | Platform-dependent; not an app feature | Stability and distribution diagnostics | Not established by repository source | Controlled by Play/platform retention rather than an in-app record | PENDING — final AAB audit | PENDING — final AAB audit |
| Device or app identifiers | No advertising-ID API or permission found; `play-services-auth` remains in release runtime | No application source call to advertising/device identifiers found | Play Services authorization may process service/device/app identifiers internally; no developer backend path found | Google Play Services/Google only if the embedded library does so | Source is insufficient to classify transitive SDK behavior | Source is insufficient to classify transitive SDK behavior | Drive authorization optional | Authorization/security if processed | PENDING — final AAB audit | Disconnect/clear app data where controlled by app; provider deletion otherwise | PENDING — final AAB audit | PENDING — final AAB audit |

### Off-device path audit

| Behaviour | Trigger and schedule | Data and recipient | Security/metadata | Deletion and declaration state |
|---|---|---|---|---|
| Encrypted Google Drive backup | User explicitly connects Drive; pending changes enqueue a 15-minute-debounced run and a retained periodic check runs every 24 hours with network, battery and storage constraints | Authenticated encrypted vault objects and attachment file chunks to the user's private Drive app-data space | Drive API HTTPS; scope is exactly `https://www.googleapis.com/auth/drive.appdata`; Drive file IDs, names, roles and bounded app properties are provider metadata | Delete attachment content leaves attachment records and other backup history; delete history leaves a non-secret terminal safety marker and does not delete the separate Android package. Disconnect is not remote deletion. PENDING — final AAB audit |
| Drive account binding | Explicit account authorization or later silent authorization for an enabled backup | Google account/grant data through Play Services; Drive permission ID from `about.get`; per-install HMAC digest and configuration remain locally after disconnect for same-account reconnection | Access token and account handle are session-only in app source; raw permission ID/email/profile are not persisted | Disconnect stops work, marks the persisted configuration `DORMANT`, and attempts scope revocation; clear app data/uninstall removes local state. PENDING — final AAB audit |
| Android backup package | User prepares the package; Android performs eligible cloud backup/device transfer | Exactly `files/android_backup/open_tasks_portable_v1.otb`, containing encrypted snapshot/attachment record metadata but not attachment file bytes | Android 12+ cloud backup requires encryption capabilities; device transfer includes the package. Legacy `fullBackupContent` excludes every domain, so it does not broaden the package | Reprepare or clear app data removes the local package; platform-held copy follows Android/Google controls. PENDING — final AAB audit |
| Plaintext CSV/Markdown/report export | User selects export content and a destination; no automatic export | CSV tables (tasks, projects, time entries, notes), project Markdown, and optional HTML report to a user-selected document provider/app | Plaintext by design; the app does not promise destination encryption or transit protection | User deletes the destination copy. Play's user-initiated-transfer treatment remains PENDING — final AAB audit |
| Encrypted `.otvault` import/export | User chooses a document and supplies/sets a recovery passphrase | Authenticated encrypted vault archive, including backed-up records and attachment chunks, through a user-selected document provider | Archive payload is encrypted/authenticated; provider transport is outside the app's guarantee | User deletes exported archive; imported staging is local and bounded. PENDING — final AAB audit |
| FileProvider sharing | User explicitly shares a prepared attachment or report | Cached file exposed to the chosen receiving app through a temporary content URI grant | Provider is non-exported and grants URI permission; content format itself may be plaintext | Temporary cache/app data can be cleared; recipient copy is controlled by the user/recipient. PENDING — final AAB audit |
| Calendar-provider handoff | User confirms adding a scheduled task to calendar | Task title, project name and start/due dates through `ACTION_INSERT` to the selected calendar provider/app | No calendar permission, stored event ID or result contract; the provider owns its insert screen and transport | User/provider controls deletion of the destination calendar entry. Play collection/sharing classification remains uncertain. PENDING — final AAB audit<br>PENDING — current Console wording |
| Google Play diagnostics | Distribution/platform operation, not an app UI or embedded diagnostics flow found by this audit | Google Play may supply Android vitals and other diagnostics to the developer | Separate from the app's source/dependency behavior; no analytics, Firebase or crash-reporting SDK was found | PENDING — final AAB audit<br>PENDING — current Console wording |

## Dependency, network, scope and manifest audit

Repository audit on 2026-08-27 used the release runtime classpath with
`ANDROID_HOME` and `ANDROID_SDK_ROOT` set to `/Users/kk/Library/Android/sdk`.
The generated report is temporary at
`/private/tmp/open-tasks-release-runtime-dependencies.txt` and is not a release
artifact.

| Surface | Repository evidence | Status |
|---|---|---|
| Embedded SDKs | Release runtime contains AndroidX, Kotlin, Room/SQLCipher, Tink/Bouncy Castle, Hilt and `com.google.android.gms:play-services-auth:21.6.0`; no Firebase, analytics, telemetry, advertising, billing or crash-reporting SDK match | PASS — repository dependency audit; PENDING — final AAB audit |
| Network | Production application origins found are Google Drive (`https://www.googleapis.com`) and the fixed privacy URL opened in an external browser; no developer-operated application backend was found | PASS — repository source audit; PENDING — final AAB audit |
| OAuth scope | Production authorization requests one scope: `https://www.googleapis.com/auth/drive.appdata` | PASS — repository source audit and current Cloud configuration; PENDING — final AAB audit |
| Broad source-scan matches | `Authorization` is the Drive flow; `toGoogleSignInAccount()` is a Play Services result conversion; `crash` occurrences describe crash-safe code/tests, not a reporting SDK; HTTP XML namespace literals and test/example URLs are non-network matches | Reconciled |
| Advertising ID | No `AD_ID`, `AdvertisingId`, advertising-ID API or ads dependency found | PASS — repository source/dependency audit; PENDING — final AAB audit |
| Backup | `allowBackup=true`; Android 12+ extraction rules include only the encrypted portable package and require cloud encryption capability; legacy rules exclude all domains | PASS — repository manifest/rules audit; PENDING — final AAB audit; PENDING — runtime restore qualification |
| Exported components | Main activity is exported for launcher, quick add, text share and process-text entry; Quick Settings tile service is exported behind `BIND_QUICK_SETTINGS_TILE`; other declared receivers/providers are not exported | PASS — repository manifest audit; PENDING — final AAB audit; PENDING — runtime qualification |

## App Content and reviewer evidence

`PASS — repository evidence` means the checked source/dependency surface supports
the draft. It does not represent a submitted or accepted Console answer.

| Topic | Draft position and rationale | Evidence | Status |
|---|---|---|---|
| Ads | No | No ads SDK, advertising ID permission/API or ad surface found | PASS — repository evidence; Console wording PENDING |
| App access | Core app is unrestricted; a fresh launch opens a local workspace and no login credentials are required | App launch/local vault flow; optional authorization is isolated to Drive backup/recovery | PASS — repository evidence; Console wording PENDING |
| Target audience | 13 and older; not directed to children or Families | Fixed release decision; listing has no child-directed claims | PENDING — current Console wording and owner declaration |
| Content rating | Ordinary user-authored task/productivity content; do not copy or predict a rating | Product surfaces only; actual answers must come from the current questionnaire | PENDING — current Console questionnaire |
| Billing and purchases | No billing or in-app purchases | No billing dependency, permission or purchase flow found | PASS — repository evidence; Console wording PENDING |
| Financial features | None | No payments, lending, investing, crypto or financial-product behavior found | PASS — repository evidence; Console wording PENDING |
| Government/news/health/dating/gambling/VPN | None | Task/project productivity behavior and audited dependencies/manifests | PASS — repository evidence; each current Console question PENDING |
| Social/collaboration | None; no live sync, collaboration, multi-user sharing or social graph | Local vault plus personal backup/export paths | PASS — repository evidence; Console wording PENDING |
| App accounts and deletion | Open Tasks creates no app account, so there is no Open Tasks account-deletion flow; optional Google authorization must not be represented as an Open Tasks account | Local fresh-launch flow; Drive authorization manager | PASS — repository evidence; current account-deletion question PENDING |
| Optional Google authorization | Used only for encrypted backup/recovery in private Drive app-data; core app remains available without it | Drive authorization manager, lifecycle coordinator and More backup UI | PASS — source, audience and scope configuration; branding custom-domain verification PENDING |
| Exact alarms | Supports precise task reminders, focus phase boundaries and app-lock expiry; code falls back to inexact alarms when unavailable | `ReminderSystem.kt`, `FocusAlarms.kt`, `AppLockExpiry.kt` | PASS — repository evidence; restricted-permission declaration PENDING |
| Notifications | Delivers user-configured reminders, focus events and daily digest locally | Reminder/focus/digest notification code and runtime permission check | PASS — repository evidence; Console wording PENDING |
| Boot completed | Reschedules/reconciles local reminders after boot, package replacement, time or time-zone changes | Non-exported `ReminderSystemEventReceiver` | PASS — repository evidence |
| Biometric | Optional local app-lock unlock using Android biometric/device credentials; no biometric template is read by the app | `MainActivity.kt`, app-lock settings/controller | PASS — repository evidence; Console wording PENDING |
| Internet | Required only for optional Google Drive authorization/backup/recovery in app code; the privacy-policy link is opened in the user's browser | Manifest, Drive transport and fixed policy action | PASS — repository evidence; PENDING — final AAB audit |
| FileProvider | Non-exported provider grants selected apps temporary access to cached attachments/reports | Manifest, `file_paths.xml`, attachment/report share code | PASS — repository evidence |
| Exported activity/service | Main activity handles launcher, quick add and user-directed incoming text; tile service exposes quick add only to the system under `BIND_QUICK_SETTINGS_TILE` | Manifest intent filters and permission | PASS — repository evidence; PENDING — final AAB audit; PENDING — runtime qualification |
| Android backup | Allows only the prepared encrypted portable package on Android 12+ cloud backup/device transfer; legacy rules exclude all domains | Manifest and both backup-rule XML files | PASS — repository evidence; PENDING — final AAB audit; PENDING — device qualification |
| Privacy policy | Current in-app and listing URL; custom-domain replacement required before submission | More → Privacy policy opens `https://ksdaklmk.github.io/open-tasks/privacy/` | Current repository link and public deployment PASS; replacement URL, OAuth branding and final AAB audit PENDING |

### Reviewer path

1. Fresh launch opens a private local workspace; no credentials are needed.
2. Open **More** to find **Privacy policy**, import tools, and **Backup & recovery**.
3. Core tasks/projects, schedules, reminders and focus timing can be reviewed
   without Google authorization.
4. Google Drive sign-in is optional and is requested only when the reviewer
   chooses encrypted Drive backup or recovery.

Do not force an answer from this document if the current Console question is
materially different. Record its exact wording and reconcile it with the final
AAB audit first.

## Asset manifest contract

Every asset remains `PENDING — captured from final release UI` until Task 10.
All screenshot workspace content must be synthetic. Exclude account names,
account/device identifiers, notification previews, real or identifying
attachment names, recovery passphrases/secrets, debug UI and private data.

| Filename | Required dimensions | Required content | Alt text | Status |
|---|---:|---|---|---|
| `icon-512.png` | 512 × 512 | Approved Open Tasks Ember launcher icon; PNG at most 1,024 KiB | Open Tasks launcher icon. | PENDING — captured from final release UI |
| `feature-graphic-1024x500.png` | 1024 × 500 | Opaque Ember `#C64E2B` field, approved white-card/charcoal task glyph and the words “Open Tasks”; no device/provider/review/price/ranking claim | Open Tasks name and task glyph on an ember background. | PENDING — captured from final release UI |
| `phone-01-home.png` | 1080 × 1920 | Actual phone Home with today's synthetic plan and search action | Open Tasks Home showing today's synthetic plan and search. | PENDING — captured from final release UI |
| `phone-02-tasks.png` | 1080 × 1920 | Actual phone Tasks with useful synthetic grouping, checklist and reminder state | Grouped synthetic tasks with a checklist and reminder. | PENDING — captured from final release UI |
| `phone-03-project.png` | 1080 × 1920 | Actual phone project planning/detail surface with synthetic content | A synthetic project planning and detail screen. | PENDING — captured from final release UI |
| `phone-04-more-backup.png` | 1080 × 1920 | Actual phone More showing Privacy policy and Backup & recovery without implying Drive is required | More options with Privacy policy and optional Backup and recovery. | PENDING — captured from final release UI |
| `large-01-home.png` | 1920 × 1080 | Actual expanded/adaptive Home with a synthetic workspace | Open Tasks Home in its expanded layout with synthetic tasks. | PENDING — captured from final release UI |
| `large-02-project-board.png` | 1920 × 1080 | Actual large-screen project board/planning layout with synthetic content | A synthetic project board in the large-screen planning layout. | PENDING — captured from final release UI |
| `large-03-schedule.png` | 1920 × 1080 | Actual large-screen Schedule with synthetic dates/tasks | Schedule in the large-screen layout with synthetic planned tasks. | PENDING — captured from final release UI |
| `large-04-more-backup.png` | 1920 × 1080 | Actual large-screen More/Backup & recovery without account identity or mandatory-Drive implication | More and optional Backup and recovery in the large-screen layout. | PENDING — captured from final release UI |

No translation, custom store listing, store-listing experiment or video is part
of the first submission.
