# API 36 Ctrl+K focus diagnostic design

## Scope

Investigate the remaining API 36 CI failure in
`ShortcutRootWiringInstrumentedTest.ctrlKOpensSearchAndFocusesTheQueryField`
on `test-fix`. The work is test-only; production code, workflow logic,
formats, and the API 37.0 F6 blocker are out of scope.

## Root cause

The test proves that `Ctrl+K` creates `SearchSurface`'s dialog before it
fails. It then calls `View.dispatchWindowFocusChanged(true)` on the dialog
root and waits for `TYPE_VIEW_FOCUSED`. That method only routes a view-tree
window-focus callback; it does not make the WindowManager give the dialog
input focus. A system accessibility focus event is therefore not a valid
consequence of the synthetic callback.

## Design

Replace the opaque accessibility-event wait with one test-only focus-state
diagnostic at the same point in the existing scenario. It must record enough
native and accessibility state to distinguish these outcomes:

1. the query received input focus but Android emitted no matching event;
2. the dialog never received actual window focus, so the query cannot receive
   input focus; or
3. the dialog/window discovery is wrong.

The first CI bench runs this single hypothesis. If it identifies a test-harness
correction that preserves both assertions (real `Ctrl+K` opens Search and the
query receives focus), make one minimal test-only correction and use the
second, final bench. If it instead shows that headless API 36 cannot establish
the precondition, stop and report; do not fake focus or change product code.

## Controller decision

Bench 1 proved that the API 36 headless runner does not grant the Dialog real
window focus. The controller selected a capability-based exception: after
proving that Ctrl+K opens the Dialog, skip only the query-focus assertion when
`dialogRoot.hasWindowFocus()` is false. On a runner with real Dialog focus, the
original focused-EditText accessibility assertion and its timeout diagnostic
remain unchanged. This supersedes the diagnostic plan's no-skip rule only for
the explicitly unavailable window-focus capability; it does not synthesize
focus or claim that the headless image verified it.

## Verification

Before a bench, run the required local JVM/lint/debug and Android-test assembly
gates, the workflow verifier, and `git diff --check`. The two-run cap applies
only to this authorised follow-up. The exception source passed those local
gates and is intentionally paused uncommitted; bench 2 has not been used.
Any future CI success still requires green `verify`, all six API 36
instrumented modules, and `release`; API 37.0 remains documented F6.
