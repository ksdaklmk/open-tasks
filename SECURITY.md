# Security Policy

## Supported version

Only the latest signed sideload release is supported with security fixes.
Older releases and development builds should be upgraded before a report is
validated against them.

## Reporting a vulnerability

Report suspected vulnerabilities through this repository's
[private GitHub security-advisory channel](https://github.com/ksdaklmk/open-tasks/security/advisories/new).
Do not open a public issue or publish exploit details before a fix is
available.

Include the affected release, Android version, device type, preconditions,
impact, and the smallest reproducible proof that does not contain another
person's data. Reports are acknowledged within three business days. The owner
will confirm validation and coordinate disclosure through the advisory.

## In scope

Security reports are in scope when they affect Open Tasks or its release
pipeline, including:

- confidentiality of vault records, keys, recovery material, backups,
  attachments, notifications, saved state, or generated exports;
- integrity of local commands, encrypted objects, imports, recovery,
  single-writer ownership, release artifacts, or dependency resolution; and
- availability of an existing vault, including data loss, permanent lockout,
  or unbounded processing of attacker-controlled input.

Open Tasks is a local-first Android application with optional Google Drive
backup and recovery. Person-directed CSV, archive, and HTML exports cross the
vault boundary by design; failures that bypass their disclosure, scope,
integrity, or cleanup controls remain in scope.

## Safe research boundary

Use only devices, accounts, vaults, and data you own or have explicit
permission to test. Do not access or alter another person's data, disrupt a
third-party service, use social engineering, run destructive or
resource-exhaustion tests against shared infrastructure, or retain private
data encountered accidentally. Stop and report privately if a test could
affect anyone else. Follow applicable law and allow a reasonable remediation
period before disclosure.

Findings that require a rooted or otherwise compromised operating system,
plaintext observation while the owner has intentionally unlocked the app, or
a malicious accessibility service authorised by the owner are outside the
confidentiality guarantee unless Open Tasks independently weakens a platform
protection. Vulnerabilities solely in an upstream service or dependency should
be reported upstream unless Open Tasks makes them exploitable in this product.

## Remediation targets

Targets begin when a report is validated:

| Severity | Target |
| --- | ---: |
| Critical | 7 days |
| High | 30 days |
| Medium | 90 days |

Critical and High findings block a release until remediated or safely
mitigated. Lower-severity findings are handled according to impact and release
risk.
