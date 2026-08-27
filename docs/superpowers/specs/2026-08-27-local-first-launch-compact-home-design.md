# Local-first Launch and Compact Home

Date: 2026-08-27. Status: **approved, not implemented**.

This design supersedes the Welcome-specific parts of the implemented
onboarding and generic CSV designs. Their implementation and qualification
records remain historical evidence; where they describe the current entry
surface, this document is authoritative.

## Outcome

Open Tasks opens a normal local workspace without asking the person to choose
an account or storage mode. A fresh installation creates the existing empty,
encrypted local vault automatically and proceeds to Home. Google remains an
optional backup and recovery provider reached deliberately from More.

Home no longer shows a time-of-day greeting. Its existing date and search
control form one compact header row, leaving more vertical space for useful
content.

## Home header

- Delete the hard-coded `Good afternoon` text; do not replace it with another
  greeting or clock-dependent copy.
- Keep the existing UK-English date format and search action.
- Keep the date as the screen heading for accessibility after the greeting is
  removed.
- Do not change Home data, ordering, spacing outside the header, or search
  behavior.

## Fresh-launch behavior

Automatic creation is allowed only when all three facts are true:

1. `VaultRuntimeState` is `NoVault`;
2. recovery presentation is idle at `RecoveryPresentation.NoVault`; and
3. the app is not already in active-vault replacement mode.

The ordinary state mapping is:

| Runtime state | Presentation | Result |
|---|---|---|
| `Initializing` | any | Keep the existing neutral initialization surface. |
| `NoVault` | `NoVault` | Start the existing local-vault creation operation once and keep the neutral surface until the runtime becomes active. |
| `NoVault` | recovery in progress or failed | Keep the existing recovery shell and back behavior. |
| `Unreadable` | any | Keep the preserved-vault recovery surface; never create over it. |
| `Recovering` | any | Keep the staged recovery surface; never create a competing vault. |
| `Active` | ordinary app mode | Mount the existing workspace. |
| `Active` | replacement mode | Keep the existing recovery source and takeover flow. |

Fresh launch performs no Google authorization, provider discovery, file
picker request, or application network call. It creates the same structural
empty vault previously created by `Continue offline`; no sample project or
task is added.

The neutral bootstrap surface must not call `reportFullyDrawn` early. The
first-run startup measurement ends only when the active workspace reaches its
existing fully-drawn condition.

## Recovery and import in More

More keeps its existing top-level **Import from another app** CSV entry.
Backup & recovery keeps encrypted `.otvault` import, strict Open Tasks CSV
import, Google backup controls, Android package preparation, and system backup
settings.

Backup & recovery gains one always-available **Restore existing workspace**
action near the top of the screen. Supporting copy explains that the person
can choose Google Drive or an Android backup package and that the current
workspace remains unchanged until a verified restore is confirmed.

The action opens the existing active-replacement recovery shell. That shell
continues to provide:

- **Find Google Drive backups**;
- **Use Android backup package**;
- authenticated candidate preparation;
- explicit takeover confirmation; and
- nondestructive Back navigation to the active workspace.

No second recovery coordinator, parser, picker, or replacement mechanism is
introduced. Existing staging and activation guarantees remain authoritative.

## Welcome removal and cleanup

The Welcome composable, its Google artwork and copy, its adaptive-layout
tests, and its entry from `MainActivity` are deleted. The Welcome-only generic
CSV handoff is also deleted: generic CSV migration remains available from More
and continues to use the active-workspace command and Undo path.

The first transition from no runtime to an active local runtime clears the
Activity ViewModel store, just like later runtime replacements. Welcome
previously kept that store alive solely to carry transient CSV rows into the
new vault; after that handoff is removed, retaining it would also retain the
bootstrap recovery presentation and break a later recovery opened from More.

Names that describe returning to Welcome or continuing offline are renamed to
describe their surviving recovery behavior. Google authorization and recovery
code are not removed because they remain reachable from Backup & recovery.

## Accessibility and adaptive behavior

- The compact Home date remains a semantic heading and search retains its
  existing accessible name and 48 dp target.
- **Restore existing workspace** has at least a 48 dp target, remains reachable
  at 200% font, and follows logical reading and keyboard order.
- The existing compact, expanded, folding, TalkBack, keyboard, and process
  restoration contracts continue after the Welcome-specific setup steps are
  removed.

## Performance and compatibility

- Replace the obsolete Welcome startup benchmark with one cold first-run
  benchmark that clears app data and measures automatic local-vault creation
  through usable Home.
- Do not retain a warm first-run benchmark: after the first successful launch,
  the vault exists and the existing empty-workspace warm benchmark is the
  correct measurement.
- Preserve Room v9, authenticated backup and `.otvault` format v1, Android
  backup rules, minimum/target SDK values, permissions, signing, and release
  identity.
- Add no dependency, database migration, provider call, analytics event, or
  storage preference.

## Acceptance criteria

- Home displays its formatted date and search action without any greeting.
- A clean `NoVault` launch automatically reaches an empty local Home without a
  tap, account chooser, recovery discovery, or network request.
- Unreadable, recovering, and active-replacement states never auto-create a
  vault.
- More still exposes generic CSV import.
- More > Backup & recovery exposes **Restore existing workspace** and opens the
  existing Drive/Android recovery source screen, including immediately after
  the automatically created first local vault.
- Back from recovery leaves the active workspace unchanged; confirmed restore
  retains the existing verified staging/takeover path.
- Welcome UI/resources and Welcome-only CSV handoff state have no remaining
  production or test callers.
- First-run startup is reported fully drawn only after the active workspace is
  ready.
