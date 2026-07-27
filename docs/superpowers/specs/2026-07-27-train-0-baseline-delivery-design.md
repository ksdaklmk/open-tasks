# Train 0 — Baseline and Delivery Design

## Goal

Turn the completed but intentionally dirty P1/P2 working tree into a verified,
recoverable programme baseline and establish trustworthy local and GitHub
delivery gates before cloud credentials or signing material exist.

## Scope

This train covers P0-R08 and P3-T00 through P3-T03:

- Preserve and verify the current completed working tree.
- Create an intentional local checkpoint without sweeping unrelated files.
- Repair the GitHub Actions API 37 and expanded-device matrix.
- Pin every third-party GitHub Action to a reviewed commit SHA.
- Verify the configured raw-colour hook through the supported harness.
- Retain official Kotlin IDE formatting without adding ktlint or Spotless.
- Decline optional plugin installation because no concrete programme need
  requires it.

## Baseline preservation

Execution begins with a complete status, diff, untracked-file, schema, and
secret-pattern review. The source of truth is the current working tree plus
`HANDOFF.md`, not `origin/main`.

The baseline verification sequence is:

1. Run `git diff --check`.
2. Run the repository unit/lint/debug gate.
3. Run the affected app, data, Tasks, Projects, More, and Schedule device
   suites without uninstalling or clearing emulator data.
4. Run release assembly separately from lint to avoid the documented AGP/KSP
   race.
5. Confirm the installed workspace survives an in-place upgrade and cold
   restart.
6. Stage only the known completed P1/P2 files and the programme documentation.
7. Create a local checkpoint commit on `main`.

No push, merge, dependency-PR action, or emulator wipe is implied by the local
checkpoint.

## Continuous integration

The workflow retains a read-only token and no repository secrets. It uses:

- One unit/lint/debug-build job.
- One release-assembly job after the main verification job.
- API 36 and API 37 instrumented jobs using system images that are available
  on the configured SDK channel.
- A genuinely expanded emulator profile for expanded-only Compose coverage.
- Gradle dependency and configuration caching that is compatible with the
  existing project settings.
- Uploaded test reports and lint output without workspace data or emulator
  files.

The API matrix uses explicit device dimensions rather than labelling a compact
profile as expanded. Instrumented job commands match the module suites in
`CLAUDE.md`.

## Supply-chain pinning

Every `uses:` entry is resolved to the upstream release commit, reviewed, and
pinned by full SHA with a trailing version comment. Dependabot remains
configured to propose GitHub Actions updates. Each update is reviewed as a new
supply-chain change before its SHA advances.

OAuth, upload-key, and Play credentials are not added during this train.
Pinning is complete before any later workflow receives secrets.

## Developer workflow decisions

The existing official Kotlin IDE formatter remains the authority. The
programme does not add ktlint or Spotless because formatting enforcement would
create maintenance work without addressing a release risk.

The raw-colour hook is loaded and exercised once using an intentional
non-production sample, then the sample is removed. The hook must reject new
hex colour literals and allow documented OKLCH usage.

Optional plugin evaluation is closed without installation. A future plugin
requires a separately stated workflow problem and review.

## Exit criteria

- The current P1/P2 state has an identifiable local commit.
- Unit, lint, debug, release, and affected device verification pass.
- GitHub Actions YAML parses and its matrix represents API 36, API 37, compact,
  and expanded execution accurately.
- All external Actions are pinned to reviewed full SHAs.
- The raw-colour hook is verified.
- No formatter or optional plugin dependency is introduced.
- `HANDOFF.md` records the new baseline and exact verification evidence.
