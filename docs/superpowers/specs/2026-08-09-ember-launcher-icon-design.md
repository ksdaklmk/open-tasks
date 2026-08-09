# Ember Launcher Icon Design

- Date: 9 August 2026
- Status: approved by the user on 9 August 2026
- Scope: installed Android launcher icon only

## Goal

Replace the current launcher artwork with the approved white card glyph on an
ember `#C64E2B` background while preserving Android adaptive, round-mask, and
Material You themed-icon behaviour.

## Approved source

The source package is
`/Users/kk/Downloads/deliverables-1a-ember/`. All 18 files were inspected:
the README, adaptive and monochrome XML, background colour, ten density/shape
fallback PNGs, the 512 px Play Store image, and `.DS_Store`.

The package README names the palette and geometry contract:

- ember background: `#C64E2B`
- charcoal glyph: `#252321`
- white card
- foreground artwork scaled to the adaptive icon's 66 dp safe zone

## Runtime design

Open Tasks already exposes `@mipmap/ic_launcher` and
`@mipmap/ic_launcher_round` from `AndroidManifest.xml`. Its two adaptive icon
definitions are byte-identical to the supplied definitions, so their names,
manifest references, and XML stay unchanged.

Only these three existing runtime resources change, byte-for-byte to the
supplied versions:

- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/res/values/colors.xml`

The full-colour layer uses the supplied rounded white card, two charcoal pill
bars, and rounded ember cursor. The monochrome layer uses the supplied outline
card and filled single-colour glyph so Android can tint it as a themed icon.
The adaptive background changes from charcoal to ember.

No Kotlin, manifest, dependency, API, data, backup, permission, or exported
component changes are part of this work.

## Deliberate exclusions

- Do not add the ten `mipmap-{mdpi..xxxhdpi}` legacy PNGs. The app's minimum
  SDK is 36, so Android always uses the existing adaptive icon resources.
- Do not add `playstore/ic_launcher-playstore-512.png`. Distribution remains
  sideload-only; retain the supplied file for the parked Play Console work.
- Do not copy `.DS_Store` or the delivery README into the application.
- Do not edit the byte-identical `mipmap-anydpi-v26` adaptive definitions.

## Task boundary and verification

Add this as standalone Stage 6 Task 13 and renumber qualification and exit
gates to Task 14. Task 13 is an independent implementation/review boundary;
qualification remains free of new product changes.

Task 13 verification is static and build-based:

- prove the three installed resources exactly match their supplied files;
- prove the two adaptive definitions remain unchanged and reference the
  foreground, monochrome, and ember background resources;
- run resource processing, lint, debug APK assembly, and the full CI gate;
- run no emulator or connected suite.

Task 14 performs the device proof on the sole disposable
`Fold8_Acceptance`: confirm the installed launcher icon under the normal and
round masks and confirm the themed monochrome icon remains legible. Record the
result in `docs/qualification/stage6-daily-flow.md`.

If the Downloads package is unavailable at execution time, the implementation
plan's exact XML bodies are authoritative; do not approximate or regenerate
the artwork.
