# Train 5 — Platform Features Design

## Goal

Complete P1-L08 and P2-F05 through P2-F07 after the local schema is stable:
encrypted vault import/export, warned plaintext CSV export, Today Glance,
Quick Add refinement, app-lock title privacy, full input support, and one-way
calendar insertion.

## Encrypted `.otvault` export

The export format reuses the versioned cloud framing and contains:

- A portable manifest and recovery envelope.
- One complete workspace snapshot.
- Required operation/tombstone state.
- Every attachment metadata record and encrypted content chunk.
- A final inventory checksum.

Export requires a fresh passphrase or confirmed use of the current recovery
passphrase. It streams directly to a Storage Access Framework destination,
never stages a complete plaintext archive, and deletes partial app-private
temporary state on failure.

## Encrypted `.otvault` import

Import uses an isolated staging directory and database:

1. Read and bound the outer header.
2. Validate KDF metadata and unlock with a `CharArray` passphrase.
3. Stream and authenticate every framed object.
4. Decode records under current domain limits.
5. Validate identifiers, ownership, workflow coverage, relations, recurrence,
   zones, attachment inventory, and format compatibility.
6. Build and open a staged SQLCipher database.
7. Present record and attachment counts plus replacement consequences.
8. Close the active repository, preserve a rollback copy, and atomically
   activate the staged vault.

Import replaces the single active vault after confirmation; it does not invent
a partial merge policy. Unsupported or corrupt archives leave the active vault
unchanged.

## Plaintext CSV

CSV is export-only in v1. The user chooses tasks, projects, and time-entry
tables. Each export presents a fresh disclosure that filenames, descriptions,
notes, dates, and time information will be readable outside Open Tasks.

The exporter uses RFC 4180 quoting, UTF-8 with a header row, UK-formatted
display fields plus ISO machine-readable date/time columns, stable column
ordering, and formula-injection neutralisation for values beginning with
`=`, `+`, `-`, or `@`.

It streams to the chosen destination and retains no plaintext copy.

## Today Glance widget

The widget shows:

- Today's open task count.
- Overdue count.
- Up to three focus tasks when title privacy permits.
- Open app and Quick Add actions.

Glance reads a minimal repository projection through scheduled/widget update
work. It never opens SQLCipher from an exported receiver without the normal
application boundary.

When the app is locked or title privacy is enabled, the widget shows counts and
generic labels only. Widget actions open authenticated application UI.

## Quick Add and app lock

Quick Add retains one title field, optional project choice, bounded saved
state, and keyboard submission. Launcher shortcut and widget actions use the
same authenticated sheet. Exported intents contain no task text.

App lock uses `BiometricPrompt` with device credential fallback. It locks after
a user-selected immediate, 1-minute, 5-minute, or 15-minute background delay.
It does not alter the SQLCipher or vault-content keys.

Title privacy controls:

- Recent-app preview concealment.
- Widget task titles.
- Notification private content.
- External Quick Add presentation.

Screenshot blocking is a separate opt-in setting. Recovery and permission
dialogs remain understandable when content is concealed.

## Keyboard, mouse, and accessible actions

The app provides:

- `Ctrl+K` and `/` for search.
- `Ctrl+N` for Quick Add.
- `Ctrl+Shift+N` for project creation in Projects.
- `?` for the shortcut helper.
- `Esc` for dismissing the top transient surface.
- Enter/Space activation for focused controls.
- Visible hover and focus states.
- Context menus only where every action also has a visible or TalkBack path.

No workflow, milestone, dependency, attachment, or schedule action requires
drag. Existing up/down and explicit actions remain the accessible authority.

## Calendar insertion

Task detail and Schedule expose Add to calendar only for dated tasks.
The app builds an `ACTION_INSERT` event with:

- Task title.
- Start moment when present.
- Due moment as end or due-only context.
- Project name in description.

A preview explains the values before launching the calendar provider. The app
requests no calendar permission, stores no calendar event ID, and performs no
background synchronisation. Cancelling the provider changes no Open Tasks
record.

## Exit criteria

- `.otvault` round-trips the final schema, attachments, and recovery key across
  a clean install and rejects corrupt/oversized/old-reader fixtures.
- CSV disclosures, quoting, formula neutralisation, and cleanup pass tests.
- Widget and Quick Add respect locked/private states across reboot and process
  death.
- Keyboard, mouse, hover, focus, and non-drag alternatives pass compact and
  expanded device acceptance.
- Calendar insertion is explicit, permission-free, and one-way.
