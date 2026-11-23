# Changelog

All notable changes to BareEdit are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, the settings file,
the command's options and the keys may still change.

## [Unreleased]

### Added

- **Rich comment forms.** Inside `(comment ...)`, Evaluate top-level form evaluates the
  form that holds the cursor, not the whole comment.
- **Links in the results column.** In output and errors, a place in a project file, such as
  `core.clj:4` in an error or a stack trace, opens the file at that line.
- **Go to definition** (`F12`). It opens the file where the symbol at the cursor is
  defined, at its line, from the REPL's answer. A definition outside the project is named
  in a message.

## [0.3.0] - 2026-11-22

BareEdit finds in the whole project, in a find bar of its own, and has a read-only demo on
GitHub Pages.

### Added

- **A read-only demo on GitHub Pages.** It shows BareEdit with its own code. It cannot
  save or evaluate.
- **Find in the project** (`Mod-Shift-f`). The matches show in the find bar, grouped by
  file, and Enter or a click opens one. `F3` and `Shift-F3` go to the next and previous
  match.

### Changed

- **The find bar** is BareEdit's own, at the bottom of the editor. It uses the interface's
  font size, and it searches the file or the project.
- **BareDOM 4.22.0.** A click on the words next to a find option toggles it, through
  BareDOM's own checkbox label.

## [0.2.0] - 2026-11-20

BareEdit installs from Clojars with bbin, and the file tree is faster in large folders.

### Added

- **Install with bbin.** `bbin install com.github.avanelsas/bareedit --mvn/version 0.2.0`
  adds the `bareedit` command. Building from a clone is no longer needed.

### Changed

- **BareDOM 4.21.1.** Selecting a file in the tree is faster in a large folder. The command
  palette scales with the interface size, and its scrollbar is dark in dark mode.

## [0.1.0] - 2026-11-19

The first release.

### Added

- **Project.** `bareedit` opens the folder it runs in, or the folder it is given, in the
  browser. The editor reaches only that folder, and stops a few seconds after its last
  browser tab closes.
- **Files.** A file tree in the order other editors use, Go to file, new file, rename,
  save and save all. Files changed on disk reload, or the editor asks before it replaces
  your changes. A folder that cannot be read is left out, and a warning names it.
- **Command palette.** Every command, in groups, with its keys.
- **Clojure evaluation** through the project's own nREPL: the form at the cursor, the
  top-level form or the whole file, with interrupt. Results show next to the code, with
  output, values and errors.
- **ClojureScript evaluation** through shadow-cljs, with `--build` to pick a build.
- **Completion** from the REPL in Clojure files, and from the words of the file
  elsewhere. Docs for the symbol at the cursor.
- **Structural editing** in Clojure files: slurp, barf, unwrap, kill to end of form,
  expand and shrink selection, toggle comment and reindent.
- **Languages.** Clojure, Markdown, HTML, CSS, JSON and JavaScript each get their own
  colours. Any other file is plain text.
- **Settings** in `~/.config/bareedit/config.edn`, opened from the palette with Open
  settings: a BareDOM theme for light and dark mode, the interface size, the code font
  and size, code colours, and a guide line at column 80. A save applies them at once.
- **Command line.** `--help`, `--version`, `--no-open` and `--build`. An unknown option
  stops with a message.

[Unreleased]: https://github.com/avanelsas/bareedit/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/avanelsas/bareedit/releases/tag/v0.3.0
[0.2.0]: https://github.com/avanelsas/bareedit/releases/tag/v0.2.0
[0.1.0]: https://github.com/avanelsas/bareedit/releases/tag/v0.1.0
