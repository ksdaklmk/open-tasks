# Train 1 — Insights and Cloud-Format Foundation Design

> **Direction update — 28 July 2026:** Tasks 1.1–1.5 remain accepted
> historical evidence. The unstarted Task 1.6 is replaced by the Stage 1
> local-authority foundation plan and must not be executed from this file.

## Goal

Deliver the next ready user-facing slice, P2-F04 Insights, and establish the
versioned encrypted format and key foundation required by P1-D02.

The two workstreams share a train but not a data authority: Insights is a pure
local projection, while the cloud format serialises existing durable records.

## Insights domain model

`InsightsEngine` consumes an immutable `WorkspaceSnapshot`, a half-open
reporting interval, and the current instant. It produces `InsightsSnapshot`
with the selected interval, comparison interval, metric values, denominators,
ordered table rows, and data-quality flags.

Supported intervals are the trailing 7, 30, and 90 local calendar days. The
comparison is the immediately preceding interval of equal length. Filters may
limit the projection to selected projects and tags.

Metrics are defined as follows:

- **Completed work:** tasks whose `completedAt` falls inside the interval,
  compared with the preceding interval.
- **Overdue work:** active, incomplete, non-Bin tasks whose due instant is
  earlier than `now`, grouped into 1–7, 8–30, and more-than-30-day age bands.
- **Estimate versus actual:** estimated duration and completed time-entry
  duration for tasks completed in the interval. Tasks without estimates remain
  visible but do not enter the estimate total.
- **Project time:** each completed time entry is clipped to the report interval
  and attributed to its task's project or Inbox.
- **Tag time:** the same clipped duration is repeated for each assigned tag.
  The UI labels tag totals as non-additive because a task can have several
  tags.
- **Milestone health:** each open milestone reports its due state, assigned
  open/completed task counts, overdue assigned work, and the owning project's
  explicit health. The engine does not invent a hidden health score.

Any completed entry participating in `timeEntryConflicts` is excluded from
trusted actual totals and reported in a separate conflicted-duration value.
Users may include it for exploration, but the default comparison remains
qualified and trustworthy.

## Insights UI

Insights is a More subdestination. The overview shows concise metrics and
plain-language comparison badges, followed by completion, overdue,
estimate/actual, project/tag time, and milestone sections.

Charts use the restrained design system and never encode status by colour
alone. Every chart has a switchable table/text representation with the same
ordering and values. Empty, no-estimate, no-time, all-conflicted, and partial
filter states have explicit copy.

The More overview may show one compact weekly summary derived from the same
engine. Home may link to Insights but does not gain a dashboard grid.

## Vault-content key

Every vault gains a random Tink vault-content key independent from the
SQLCipher database key. Android Keystore wraps the local copy. A Drive-enabled
vault additionally stores a passphrase-wrapped recovery envelope for the same
key.

The key encrypts cloud snapshots, operation segments, attachment metadata, and
attachment chunks. Creating or changing a recovery passphrase rewraps the key;
it does not reencrypt the vault.

Loss of the local Keystore wrapper fails closed. Recovery requires the
passphrase envelope. A local-only vault without a recovery envelope explains
that Keystore loss remains unrecoverable until recovery is configured.

## Cloud object framing

Every object begins with a canonical UTF-8 JSON header followed by ciphertext.
The header contains:

- Magic value and object family.
- Schema, crypto, and minimum-reader versions.
- Vault and opaque object identifiers.
- Ciphertext length and SHA-256 ciphertext checksum.
- Chunk index/count for chunked objects.

The header contains no task text, filenames, account details, Drive IDs, or
plaintext record metadata. Tink associated data includes the canonical header
identity fields so headers cannot be swapped between objects.

Object families and hard limits are:

| Family | Limit |
|---|---:|
| Manifest | 256 KiB total |
| Snapshot | 64 MiB decrypted payload |
| Operation segment | 4 MiB and 1,000 operations |
| Attachment | 100 MiB plaintext |
| Attachment chunk | 4 MiB plaintext |

Parsers reject negative sizes, integer overflow, duplicate identifiers,
unsupported versions, excessive nesting or collection counts, invalid zones,
unknown required record kinds, checksum mismatch, and AEAD failure before
returning domain records.

## Manifest, snapshots, and operations

The manifest holds:

- Vault format compatibility.
- Recovery-envelope metadata.
- Current and previous verified snapshot pointers.
- Known device identifiers, last-seen times, and immutable segment cursors.
- Attachment object inventory.

Each device writes immutable operation segments in HLC order. A segment closes
at 1,000 operations or 4 MiB. The coordinator creates a complete encrypted
snapshot after 5,000 new operations or seven days, whichever occurs first.
The current and immediately previous verified snapshots remain referenced.
A device inactive for 90 days becomes dormant and must resume from the current
snapshot rather than retaining an unbounded operation history.

Manifest updates use the provider's conditional revision token. A conflict
refetches the latest manifest, unions immutable device segments and valid
attachment pointers, selects the newest compatible snapshot, and retries the
conditional update. No last-writer overwrite may discard another device's
segment.

## Quarantine

Invalid remote objects are recorded locally by opaque object identity, failure
category, first/last seen time, and retry count. Raw bytes, Drive identifiers,
and cryptographic details do not enter logs or UI.

A quarantined operation segment blocks only the affected cursor. A damaged
current snapshot falls back to the previous verified snapshot plus operation
segments. An incompatible minimum-reader version blocks sync and preserves the
last valid local state.

## Exit criteria

- Every metric definition has pure unit coverage, including time clipping,
  tag non-additivity, empty data, and overlap qualification.
- Insights passes compact/expanded, table alternative, 200% text, keyboard,
  and accessibility tests.
- Key wrapping, recovery envelope, framing, checksums, bounds, format
  compatibility, manifest merge, and quarantine have golden and tamper tests.
- No cloud object contains plaintext workspace content.
- The cloud format can round-trip the complete current Room schema.
