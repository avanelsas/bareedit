# BareEditClj

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/baredom_darkmode.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/baredom_lightmode.svg">
    <img alt="BareDOM logo" src="docs/assets/baredom_lightmode.svg" width="160">
  </picture>
</p>

A small, local, browser-based editor for Clojure and ClojureScript. Run `bare-edit` in a project folder and it opens an editor for that folder.

## The REPL

BareEditClj evaluates Clojure through the project's own nREPL. It never starts one: start it in the project folder, before or after the editor, and the editor finds it from `.nrepl-port`, or else from `.shadow-cljs/nrepl.port`. When the REPL stops or restarts, the next evaluation connects again.

```sh
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.7.0"}}}' \
  -J-Djdk.attach.allowAttachSelf -M -m nrepl.cmdline
```

On Java 20 and later, nREPL can stop running code only when the JVM has `-Djdk.attach.allowAttachSelf`. Without it, an interrupt frees the session but the code keeps running, and the editor says so.

| Command | Keys | What it does |
|---|---|---|
| Evaluate form | `Mod-Enter` | Evaluates the selection, or else the form that ends at the cursor, or else the innermost form around it |
| Evaluate top-level form | `Mod-Shift-Enter` | Evaluates the top-level form at the cursor |
| Evaluate file | palette | Loads the text of the file, saved or not |
| Interrupt evaluation | `Mod-.` | Stops the newest evaluation that still runs |
| Show docs | palette | Shows the docs of the symbol at the cursor |
| Clear results | palette | Empties the results column |
| Toggle results column | palette | Hides or shows the results column |

`Mod` is Cmd on macOS and Ctrl elsewhere. The command palette opens with `Mod-Shift-p` or `F1`.

- **Namespaces.** A form is evaluated in the namespace of its file's `ns` form, or in `user` when there is none (`cljs.user` in a `.cljs` file). If that namespace is not loaded yet, the result says so: evaluate the file first.
- **Results.** Each evaluation gets an entry in the results column, newest first, with its output, its value and any error. Values are pretty-printed. A long value is folded, with a button that shows all of it. A click on an entry's header goes to its code.
- **Completion.** In `.clj` and `.cljc` files, completions come from the REPL, with the kind and the namespace of each. With no REPL, and in every other file, the words of the open file are offered. `Ctrl-Space` shows them on demand.
- **ClojureScript.** `.cljc` files go to the Clojure session, and `.cljs` files go to shadow-cljs, see below.

## ClojureScript

BareEditClj evaluates ClojureScript through the project's shadow-cljs. Start shadow-cljs in the project folder with a watch, and run the app so that a JS runtime connects:

```sh
npx shadow-cljs watch app
```

- **The build.** The editor uses the build whose watch runs. When several run, start the editor with `--build` and the name of one, such as `bare-edit --build app`. Each browser tab keeps the build it started with.
- **Messages.** When no watch runs, when no JS runtime is connected, or when the watch stops, the result says so. Start it again, and the next evaluation reconnects.
- **Namespaces.** A namespace that is not loaded is created empty, with no message, so evaluate the file first.
- **Evaluate file** sends the text of the file, saved or not.
- **Interrupt.** A ClojureScript evaluation cannot be interrupted, because shadow-cljs cannot stop running JavaScript. The editor says so.
- **Completion and docs.** In `.cljs` files, completion offers the words of the open file, and docs are not available.
- **Clojure.** Without a `.nrepl-port`, Clojure evaluations use shadow-cljs's own JVM.

## Development

| Task | Command |
|---|---|
| Run the server and shared tests (bb); the REPL tests start an nREPL with the Clojure CLI | `bb test` |
| Run the shared and browser tests in ClojureScript (Node) | `bb cljs-test` |
| Build the browser bundle | `bb build` |
| Lint | `clj-kondo --lint src test dev` |
| Check every docstring against `.claude/rules/docstrings.md` (exits 1 on a problem, for CI) | `bb docstrings` |
| Start the editor for the current folder | `bb --config <path>/bb.edn start` (add `--no-open` to skip the browser) |

## Layout

| Folder | What lives there |
|---|---|
| `src/shared` | The protocol as data, used by the server and the browser |
| `src/server` | The bb server: HTTP, WebSocket, the security checks, and the client of the project's nREPL |
| `src/app` | The browser app (ClojureScript, shadow-cljs) |
| `dev` | Development tools that do not ship, such as the docstring checker |
| `test` | Table tests for the pure functions and the event steps, server tests over a real WebSocket, and REPL tests against a real nREPL |
| `.claude/rules` | Rules for Claude Code, such as how docstrings are written |

## License

MIT, see [LICENSE](LICENSE). The dependencies keep their own licenses: CodeMirror, Lezer and BareDOM are MIT, `@nextjournal/lezer-clojure` is ISC, clojure-mode is EPL-2.0, ClojureScript and shadow-cljs are EPL-1.0, and the Google Closure Library is Apache-2.0.
