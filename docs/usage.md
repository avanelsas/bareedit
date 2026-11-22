# Using BareEdit

The details behind the [README](../README.md).

## Keys

`Mod` is Cmd on macOS and Ctrl elsewhere. The command palette (`Mod-Shift-p` or `F1`) lists every command with its keys.

| Keys | Command |
|---|---|
| `Mod-Shift-p` or `F1` | Command palette: every command, with its keys |
| `Mod-p` | Go to file |
| `Mod-s` / `Mod-Alt-s` | Save / save all |
| `Mod-Enter` | Evaluate the form at the cursor |
| `Mod-Shift-Enter` | Evaluate the top-level form |
| `Mod-.` | Interrupt the evaluation |
| `F2` | Rename the file |
| `F12` | Go to the definition of the symbol at the cursor |
| `Alt-w`, `Alt-[`, `Alt-]` | Close, previous and next tab |
| `Mod-f` | Find and replace in the file |
| `Mod-Shift-f` | Find in the project |
| `F3` or `Mod-g`, `Shift-F3` or `Mod-Shift-g` | Next and previous match |
| `Ctrl-Space` | Show completions |

The keys for structural editing are in [Structural editing](#structural-editing).

## Evaluation

- **Evaluate form** (`Mod-Enter`) evaluates the selection, or else the form that ends at the cursor, or else the innermost form around it.
- **Evaluate top-level form** (`Mod-Shift-Enter`) evaluates the top-level form at the cursor.
- **Evaluate file** loads the text of the file, saved or not.
- **Interrupt** (`Mod-.`) stops the newest evaluation that still runs. Without `-Djdk.attach.allowAttachSelf` on Java 20 and later, the session is freed but the code keeps running, and the editor says so.
- **Namespaces.** A form is evaluated in the namespace of its file's `ns` form, or in `user` when there is none (`cljs.user` in a `.cljs` file). An unloaded namespace gives a message in Clojure. In ClojureScript it is created empty, with no message.

## Results

Each evaluation gets an entry in the results column, newest first, with its output, its value and any error. Values are pretty-printed, and a long value is folded behind a button. A click on an entry's header goes to its code. In output and errors, a place in a project file, such as `core.clj:4` in `at demo.core/boom (core.clj:4)` or in a stack trace printed with `clojure.repl/pst`, is a link that opens the file at that line. A place outside the project, such as `Numbers.java:190`, stays text. **Clear results** and **Toggle results column** are in the palette.

## Completion, docs and definitions

In `.clj` and `.cljc` files, completions come from the REPL, with the kind and namespace of each, and **Show docs** shows the docs of the symbol at the cursor. Without a REPL, and in every other file, completion offers the words of the open file. `Ctrl-Space` shows completions on demand.

**Go to definition** (`F12`) opens the file where the symbol at the cursor is defined, at its line. It asks the REPL, so it works in `.clj` and `.cljc` files, for code that is loaded. A definition outside the project, such as in `clojure.core` or a library, is named in a message, since the editor opens project files only. So is a file that two project paths could be, such as `a/core.clj` under both `src` and `test`.

## ClojureScript

- `.cljs` files go to shadow-cljs, and `.cljc` files to the Clojure session.
- The editor uses the build whose watch runs. With several, start it with `--build`. Each browser tab keeps the build it started with.
- When no watch runs, no runtime is connected, or the watch stops, the result says so. The next evaluation reconnects.
- A ClojureScript evaluation cannot be interrupted, and docs are not available.
- Without a `.nrepl-port`, Clojure evaluations use shadow-cljs's own JVM.

## Find

The find bar opens at the bottom of the editor, so it never covers the text. Selected text on one line becomes the query. `Mod-f` searches the open file and `Mod-Shift-f` the project. Pressing the other key switches, and keeps the query.

- **In the file**, every match is marked. Enter, the down arrow or `F3` selects the next, and Shift-Enter, the up arrow or `Shift-F3` the previous. The bar shows where you are, such as "3 of 14". The row below replaces the selected match, or every match at once. A regular expression's replacement can use `$1` for its first group.
- **In the project**, every file in the tree is searched as you type. The matches are grouped by file, with the query marked in each line. The arrow keys select a match and Enter opens it, or click one. `F3` and `Shift-F3` open the next and the previous. A click on a file's name folds its matches. The count is of lines, so a line with three matches counts once. The first 500 matching lines show. Opening the bar again searches again, and so does any change on disk that the editor sees, such as a save or a rename. Binary files, and files over 1 MB, are left out.
- **Match case**, **Whole word** and **Regular expression** change the query. A match never runs across lines. Escape closes the bar.
- The project search reads the files on disk, so unsaved changes are not searched.

## Files

- **The tree** lists the project's files. In a git repository, git decides which. Otherwise the editor skips `.git`, `node_modules`, `target`, `.cpcache` and `.shadow-cljs`. A folder that cannot be read is left out, and a warning names it.
- **New file** asks for a path, and the file is written at the first save. **Rename file** (`F2`) moves the open file.
- **Changes on disk.** When the window gets focus, open files are checked. A file changed elsewhere reloads, or the editor asks whether to keep your changes or take the disk's.

## Languages

The file's extension decides how it is edited. Clojure files (`.clj`, `.cljs`, `.cljc`, `.edn`, `.bb`) get Clojure colours, indentation and structural editing. Markdown files (`.md`) get Markdown colours, and Enter continues a list. HTML (`.html`, `.htm`), CSS (`.css`), JSON (`.json`) and JavaScript (`.js`, `.mjs`, `.cjs`, `.jsx`) get their own colours and close their brackets, and HTML colours its `<style>` and `<script>` blocks as CSS and JavaScript. Any other file is plain text. The status bar names the language. Structural editing works in Clojure files only, and evaluation in `.clj`, `.cljc` and `.cljs` files.

## Structural editing

| Keys | Command |
|---|---|
| `Ctrl-Shift-0` / `Ctrl-Shift-9` | Slurp forward / backward |
| `Ctrl-Shift-]` / `Ctrl-Shift-[` | Barf forward / backward |
| `Alt-s` | Unwrap |
| `Ctrl-k` | Kill to end of form |
| `Alt-Up` / `Alt-Down` | Expand / shrink selection |
| `Mod-/` | Toggle comment |

**Reindent the current form** is in the palette. Enter and Tab indent the way clojure-mode does, following Tonsky's "Better Clojure Formatting".

## Settings

Run **Open settings** from the command palette. It opens the settings file, `~/.config/bareedit/config.edn`, one for every project, with every setting and its default value as a comment. Saving applies it at once.

- **`:preset`** is the colour theme: `default`, `ocean`, `forest`, `sunset`, `neo-brutalist`, `aurora`, `mono-ai` or `warm-mineral`. Each has light and dark colours, and the system setting picks between them.
- **`:ui-font-size`** scales the whole interface, keeping each part's size relative to the others. Without it, the browser's default is used, normally 16.
- **`:font-family`** and **`:font-size`** set the editor and the results column, and do not follow `:ui-font-size`.
- **`:ruler`** is the column of the guide line, from 40 to 200, or `false` for none. It is 80 by default. Long lines still wrap at the window's edge.
- **`:syntax`** overrides the colours `:comment`, `:keyword`, `:string`, `:constant`, `:function`, `:type` and `:tag`, each a CSS colour, under `:light` and `:dark` apart.
- A wrong value or an unknown key is ignored, and a warning says so. After editing the file in another editor, reload the page (`Mod-r`).
