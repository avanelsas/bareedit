<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/bareedit_darkmode.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/bareedit_lightmode.svg">
    <img alt="BareEdit" src="docs/assets/bareedit_lightmode.svg" width="280">
  </picture>
</p>

A small, local, browser-based editor for Clojure and ClojureScript, with a file tree, a command palette, structural editing and evaluation through your project's own REPL. It also edits Markdown, HTML, CSS, JSON and JavaScript, and any other file as plain text.

![BareEdit with a Clojure file open and two results](docs/assets/screenshot.jpg)

**Status:** early and in daily use, tested on macOS. It runs on your machine only, for one user, and is not on Clojars yet. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Requirements

- [Babashka](https://babashka.org) to run the editor.
- Java, the [Clojure CLI](https://clojure.org/guides/install_clojure) and Node with npm, once, to build it.
- To evaluate code: an nREPL in your project for Clojure, or shadow-cljs for ClojureScript.

## Install

```sh
git clone https://github.com/avanelsas/bareedit.git
cd bareedit
npm ci
bb build
```

Then add an alias to your shell, in `~/.zshrc` or `~/.bashrc`:

```sh
alias bareedit='bb --config /path/to/bareedit/bb.edn start'
```

## Start

```sh
cd my-project
bareedit                 # opens the editor for this folder
bareedit ../other        # or for another folder
bareedit --build app     # when several shadow-cljs builds run
bareedit --help          # the options, and --version for the version
```

The editor opens in your browser and reaches only that folder. Closing the browser tab stops it a few seconds later. A reload keeps it running.

## Keys

`Mod` is Cmd on macOS and Ctrl elsewhere.

| Keys | Command |
|---|---|
| `Mod-Shift-p` or `F1` | Command palette: every command, with its keys |
| `Mod-p` | Go to file |
| `Mod-s` / `Mod-Alt-s` | Save / save all |
| `Mod-Enter` | Evaluate the form at the cursor |
| `Mod-Shift-Enter` | Evaluate the top-level form |
| `Mod-.` | Interrupt the evaluation |
| `F2` | Rename the file |
| `Alt-w`, `Alt-[`, `Alt-]` | Close, previous and next tab |
| `Mod-f` | Find and replace |
| `Ctrl-Shift-0`, `Ctrl-Shift-]` | Slurp and barf forward (`9` and `[` go backward) |

## Connect a REPL

**Clojure.** Start an nREPL in the project folder, before or after the editor. BareEdit finds it through `.nrepl-port` and reconnects when it restarts.

```sh
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.7.0"}}}' \
  -J-Djdk.attach.allowAttachSelf -M -m nrepl.cmdline
```

The `-J` flag lets Interrupt stop running code on Java 20 and later.

**ClojureScript.** Start a shadow-cljs watch and open your app, so a JavaScript runtime connects:

```sh
npx shadow-cljs watch app
```

Code is evaluated in its file's namespace. When that namespace is not loaded yet, run **Evaluate file** from the palette first. More on evaluation, completion and ClojureScript is in [docs/usage.md](docs/usage.md).

## Settings

Run **Open settings** from the command palette. It opens `~/.config/bareedit/config.edn`, which lists every setting with its default as a comment. Saving applies it at once.

| Setting | What it sets |
|---|---|
| `:preset` | The colour theme: `default`, `ocean`, `forest`, `sunset`, `neo-brutalist`, `aurora`, `mono-ai` or `warm-mineral` |
| `:ui-font-size` | The size of the interface, 12 to 24 pixels |
| `:font-family`, `:font-size` | The font of the code |
| `:ruler` | The column of the guide line, 80 by default, or `false` |
| `:syntax` | Code colours, for light and dark mode apart |

## Development

| Task | Command |
|---|---|
| Server and shared tests (bb) | `bb test` |
| Browser and shared tests (Node) | `bb cljs-test` |
| Lint | `clj-kondo --lint src test dev` |
| Check docstrings | `bb docstrings` |
| Build the browser bundle | `bb build` |

The code is in `src/server` (the Babashka server), `src/app` (the browser app) and `src/shared` (the protocol both use), with tests in `test`.

## License

MIT, see [LICENSE](LICENSE). The dependencies keep their own licenses: CodeMirror, Lezer and BareDOM are MIT, `@nextjournal/lezer-clojure` is ISC, clojure-mode is EPL-2.0, ClojureScript and shadow-cljs are EPL-1.0, and the Google Closure Library is Apache-2.0.
