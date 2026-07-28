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
and an eventual move between local and encrypted Drive-primary storage.

## Positioning

A serious project workspace with the privacy and immediacy of a local-first
personal tool.

## Current Delivery Boundary

The current application remains local-only. Its encrypted workspace is fully
useful without an account or network connection. The cloud foundation now has
an independently generated vault-content key with separate recovery and local
Android Keystore wrapping, plus strict bounded canonical frames for manifests,
snapshots, operation segments and attachment chunks.

Those foundations are not a user-visible sync or recovery feature. The
authenticated cloud codec, Google Identity, Drive transport, migration,
multi-device coordination and recovery UI remain unavailable. Product copy
must not imply cloud backup, cross-device sync or reinstall recovery until
those flows pass their release gates.

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
2. Keep privacy, sync state, and recovery legible rather than invisible.
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
