# Product

## Register

product

## Platform

android

## Users

Open Tasks is for solo professionals who coordinate projects, deadlines, and
focused work across a phone, foldable, or tablet. They need to capture work
quickly, understand what deserves attention, and maintain a trustworthy project
record without operating a server or inviting a team.

## Product Purpose

Open Tasks combines fast personal task capture with the workflow, scheduling,
dependency, milestone, and time context of a full project workspace. Success
means the complete core workflow remains useful offline, resists data loss, and
continues seamlessly through rotation, resizing, folding, process recreation,
and recovery from a verified encrypted backup.

## Positioning

A serious project workspace with the privacy and immediacy of a local-first
personal tool.

## Current Delivery Boundary

The current application remains local-only. Its encrypted workspace is fully
useful without an account or network connection, and encrypted Room is the
sole live structured-data authority. The cloud foundation now has an
independently generated vault-content key with separate recovery and local
Android Keystore wrapping, plus strict bounded canonical frames for manifests,
snapshots, operation segments and attachment chunks. The implemented internal
authenticated object codec binds each frame's complete identity as AEAD
associated data, verifies its checksum before decryption, and returns typed
untrusted-object failures.

Those foundations are not a user-visible backup or recovery feature. Google
Identity, Drive transport, app-managed backup, Android Auto Backup, writer
takeover, cloud attachments, and recovery UI remain unavailable. Android
backup is still disabled. Product copy must not imply cloud backup, attachment
availability, or reinstall recovery until those flows pass their release
gates.

## Approved future contract

- Structured workspace data remains local in Room during normal use.
- App-managed encrypted backup preserves structured data for recovery.
- Android Auto Backup supplements that guarantee with one strictly
  whitelisted portable encrypted package.
- Attachment metadata remains local structured data, while attachment bytes
  are durable only in the cloud attachment service.
- Each backed-up vault has one active writer. Recovery on another device is an
  explicit takeover that advances writer ownership; it never merges two live
  workspaces.
- Backup failure never blocks local editing, and attachment failure affects
  only the file operation.

## Brand Personality

Focused, candid, and quietly capable. The interface should feel calm under a
large workload, direct about risk, and satisfying during daily maintenance
without becoming playful or decorative.

## Anti-references

Do not resemble an iOS task app transplanted onto Android, a floating bottom
pill, a collaboration-first project manager, or a card-grid SaaS dashboard.
Avoid collaborator avatars, status side-stripes, decorative gradients,
glassmorphism, cream-coloured AI-product styling, and colour-only status
communication.

## Design Principles

1. Put the next useful action ahead of workspace administration.
2. Keep privacy, backup state, active-device ownership, and recovery legible
   rather than invisible.
3. Restructure for the current window instead of stretching a phone layout.
4. Preserve context across interruption: selection, drafts, scroll, filters,
   navigation, and timer state should survive.
5. Make every irreversible or risky state explicit, while ordinary edits remain
   immediate and undoable.
6. Never hide double-counted work: preserve overlapping time records, explain
   the conflict and let the user correct either entry explicitly.

## Accessibility & Inclusion

Meet an enhanced WCAG 2.2 AA-equivalent target with 48 dp touch targets,
TalkBack names and custom actions, logical focus order, keyboard and switch
access, visible focus, high-contrast validation, non-colour status cues, reduced
motion, and layouts that remain usable at 200% font scaling. UK English ships
first, with UK spelling, day–month dates and 24-hour time; strings, dates,
plurals, sorting, and layouts must remain ready for localisation and RTL.
