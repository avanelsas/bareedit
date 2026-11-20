# BareEdit

A small, local, browser-based editor for Clojure and ClojureScript. A Babashka server (http-kit, one WebSocket, EDN messages) serves a ClojureScript page built with shadow-cljs, BareDOM web components and CodeMirror 6. See README.md for the tasks and the layout.

## How the code is shaped

- The browser state is one atom, changed only by `bare-edit.app/dispatch!`. Every event is a vector, and `bare-edit.steps/step` is a pure function from `{:state :fx}` and an event to a new `{:state :fx}`. Effects are data, run after the state is stored.
- The page is a pure function of the state and a `dispatch` function (`bare-edit.page`).
- The protocol between browser and server is data in `bare-edit.protocol`, shared by both sides.
- Prefer data over functions over macros, values over places. Keep pure logic testable without a browser.

## Checks

All five pass locally before any push; CI runs the same ones on every pull request.

    clj-kondo --lint src test dev
    bb docstrings
    bb test
    bb cljs-test
    npx shadow-cljs release app

Docstrings follow `.claude/rules/docstrings.md`.

## Working agreements

**Approval is per action.** Do the work, leave it uncommitted, say what changed, and stop. "Go", "continue" and "yes" approve the work, never the commit that ends it. "Commit this" approves one commit. The same holds for amend, rebase, tag, push, `gh pr create` and a merge.

**Propose before implementing.** A concern, an observation or a question is not a go-ahead. Say what the change would be and wait for a yes to that one. Ask one question at a time.

**Work on a branch, land it by pull request.** Never commit on `main` and never push it. Each change gets its own branch with a short name that says what it does. A pull request is squash-merged, so `main` reads one line per change, as `Title (#n)`.

**Commit messages and pull request titles state what changed and nothing else.** No trailers of any kind.

**A pull request that changes what a user sees adds its line to `CHANGELOG.md`, under Unreleased.** A release moves those lines under its version.

**BareDOM is asked, never edited.** BareDOM is published and keeps its own release cycle. When BareEdit needs something from it, say what it would have to gain and ask. A UI component comes from BareDOM first; write one here only when BareDOM cannot provide it.
