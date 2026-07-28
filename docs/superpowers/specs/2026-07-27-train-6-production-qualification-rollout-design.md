# Train 6 — Production Qualification and Rollout Design

> **Replanning required — 28 July 2026:** Keep non-cloud qualification intent,
> but replace every sync/convergence gate with the approved backup, takeover,
> stale-writer, Android Auto Backup, attachment, and recovery matrices before
> execution.

## Goal

Complete P1-D08, P0-R02, P0-R04 through P0-R07, and P0-R10; qualify the full
product and publish it globally through Google Play.

## Complete cloud matrix

The final cloud suite repeats Train 2/3 coverage with notes and attachments:

- Two and three devices.
- Offline edits and large pending outboxes.
- Duplicate, reordered, and paginated changes.
- Authentication expiry, quota, rate limit, and provider outage.
- Snapshot, segment, manifest, and attachment corruption.
- Resumable upload/download and missing chunks.
- Reinstall, new device, Keystore loss, rollback, disconnect, and cloud delete.
- Concurrent note edits, attachment deletion, and overlapping timers.

Every scenario proves both final state and absence of silent record/file loss.

## Accessibility acceptance

Automated Compose Accessibility Test Framework checks run on every feature
module. Manual acceptance covers:

- TalkBack reading order, names, roles, state, custom actions, live regions,
  dialogs, sheets, snackbars, charts, tables, notifications, and widget setup.
- Switch Access traversal and activation without touch-only paths.
- Hardware keyboard and mouse at compact and expanded sizes.
- 100%, 130%, and 200% font scale.
- High-contrast text and non-colour status cues.
- Reduced/zero animation duration.
- RTL pseudo-locale mirroring and logical navigation.
- Light and dark schemes.

P0-R02 is recorded against the currently implemented baseline early in the
train; P0-R04 repeats the complete audit after all surfaces exist.

No blocking issue may remain in a critical journey. High-impact issues in
secondary journeys also block production. Lower-impact findings require an
explicit owner, severity, and post-release target.

## Screenshot and responsive matrix

The pinned official Compose screenshot harness validates representative
feature states at:

- Compact portrait and landscape.
- Medium split-screen.
- Expanded tablet.
- Fold cover and unfolded workbench.
- Light and dark themes.
- 100%, 130%, and 200% text.
- Selected RTL pseudo-locale configurations.

Device acceptance additionally covers rotation, split-screen resizing, live
desktop resizing, and separating folds on API 36 and API 37. Reference updates
are reviewed image by image and never bulk-accepted merely to make CI pass.

## Large-data fixture

The deterministic production fixture contains:

- 10,000 tasks across 250 projects.
- 1,000 milestones and 1,000 tags.
- 100,000 completed time entries including controlled overlap cases.
- 5,000 notes/activity entries.
- 1,000 attachment metadata records with bounded synthetic encrypted content.
- A 10,000-operation pending/remote sync history.

Titles and bodies are synthetic and contain no real workspace data. Fixture
creation is available only to benchmark/test builds.

## Baseline Profiles and Macrobenchmarks

Dedicated modules generate Baseline and Startup Profiles for:

- Cold startup to usable Home.
- Open and scroll Tasks.
- Search and open a result.
- Open Project Workbench.
- Open Insights and switch range.
- Open a task's Activity & files.

Physical-device Macrobenchmarks compare no compilation with required Baseline
Profiles. Release budgets on the reference device are:

- Median cold time to initial display at or below 1,000 ms.
- Median cold time to full display at or below 1,500 ms.
- P95 frame duration below 32 ms for task/project/insight scrolling.
- No more than a 15% regression from the approved train baseline for any
  critical journey.
- Search result production within 300 ms for the large fixture.
- App-private sync merge of 10,000 valid operations within 10 seconds,
  excluding network transfer.

If a device cannot meet an absolute budget before optimisation, the train must
improve the measured baseline and document the remaining device-specific limit;
production still requires the 15% regression gate and no user-visible ANR.

## Final build and security gates

- Unit, lint, screenshot, and all device suites pass.
- Every Room and crypto/cloud format migration fixture passes.
- Release AAB builds with full R8 and resource shrinking.
- Mapping, native symbols where applicable, Baseline Profile, licences, and
  app-bundle contents are inspected.
- Manifest exports, permissions, backup rules, FileProvider paths, notification
  visibility, widget receivers, and deep links receive a final review.
- Dependency and secret scans pass.
- Threat-model residual risks and privacy flows match the final binary.
- `targetSdk` still meets the live Play deadline at submission time.

## Store and account gates

The owner completes:

- Verified Play developer identity and correct account type.
- Two-step verification and least-privilege Play roles.
- Google-generated Play App Signing key plus a separate protected upload key.
- Separate OAuth Android clients for the local debug certificate and the Play
  app-signing certificate. The upload certificate is not registered because it
  does not sign APKs delivered by Play.
- OAuth production branding and `drive.appdata` scope configuration.
- Public privacy policy linked in-app and in Play Console.
- Accurate Data Safety, app access, content rating, target audience, ads,
  government, and export-law declarations.
- Global country/region availability with `en-GB` default listing.
- Phone, tablet, foldable, and feature graphics/screenshots.
- Support email and public issue/support route.

The privacy declaration describes actual encrypted Drive transport and does not
claim that no off-device processing occurs merely because the developer cannot
decrypt it.

## Test tracks

1. **Internal:** signed AAB, app-bundle delivery, install/upgrade, pre-launch
   report, and owner smoke tests.
2. **Closed:** representative phone/fold/tablet testers exercise every critical
   journey. A qualifying newer personal account retains at least 12 opted-in
   testers for 14 continuous days and records feedback and resulting changes.
3. **Production access:** submit the required testing and readiness evidence
   when the account requires approval.
4. **Open:** globally available release candidate for at least 14 days with no
   unresolved critical/high defect.
5. **Production:** publish the approved release globally.

Google Play cannot percentage-stage the first production publication. Risk is
therefore contained in closed/open testing and managed publication.

## Staged updates and monitoring

Later production updates advance through 5%, 20%, 50%, and 100%. Each stage
holds for at least 48 hours and checks:

- Play crash and ANR vitals without adding an app telemetry SDK.
- Store reviews and support reports.
- Sync/recovery issues reported by testers or users.
- Policy and pre-launch warnings.

Any new critical/high data-loss, security, recovery, crash, ANR, or blocking
accessibility issue halts rollout. The fix uses a higher `versionCode`, the same
signing identity, full release gates, and a new staged rollout. Existing users
retain offline access while a rollout is halted.

## Exit criteria

- Complete cloud, accessibility, screenshot, performance, migration, and
  release gates pass.
- Privacy policy, OAuth, Data Safety, signing, store listing, and developer
  verification are approved.
- Internal, required closed, and open tests complete with recorded evidence.
- The initial production release is globally published.
- The staged-update and halt procedure has been rehearsed through a testing
  track and is ready for the first production update.
