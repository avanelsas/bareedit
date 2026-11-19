# Using BareEdit

The details behind the [README](../README.md).

## Evaluation

- **Evaluate form** (`Mod-Enter`) evaluates the selection, or else the form that ends at the cursor, or else the innermost form around it.
- **Evaluate top-level form** (`Mod-Shift-Enter`) evaluates the top-level form at the cursor.
- **Evaluate file** loads the text of the file, saved or not.
- **Interrupt** (`Mod-.`) stops the newest evaluation that still runs. Without `-Djdk.attach.allowAttachSelf` on Java 20 and later, the session is freed but the code keeps running, and the editor says so.
- **Namespaces.** A form is evaluated in the namespace of its file's `ns` form, or in `user` when there is none (`cljs.user` in a `.cljs` file). An unloaded namespace gives a message in Clojure. In ClojureScript it is created empty, with no message.

## Results

Each evaluation gets an entry in the results column, newest first, with its output, its value and any error. Values are pretty-printed, and a long value is folded behind a button. A click on an entry's header goes to its code. **Clear results** and **Toggle results column** are in the palette.

## Completion and docs

In `.clj` and `.cljc` files, completions come from the REPL, with the kind and namespace of each, and **Show docs** shows the docs of the symbol at the cursor. Without a REPL, and in every other file, completion offers the words of the open file. `Ctrl-Space` shows completions on demand.

## ClojureScript

- `.cljs` files go to shadow-cljs, and `.cljc` files to the Clojure session.
- The editor uses the build whose watch runs. With several, start it with `--build`. Each browser tab keeps the build it started with.
- When no watch runs, no runtime is connected, or the watch stops, the result says so. The next evaluation reconnects.
- A ClojureScript evaluation cannot be interrupted, and docs are not available.
- Without a `.nrepl-port`, Clojure evaluations use shadow-cljs's own JVM.

## Files

- **The tree** lists the project's files. In a git repository, git decides which. Otherwise the editor skips `.git`, `node_modules`, `target`, `.cpcache` and `.shadow-cljs`. A folder that cannot be read is left out, and a warning names it.
- **New file** asks for a path, and the file is written at the first save. **Rename file** (`F2`) moves the open file.
- **Changes on disk.** When the window gets focus, open files are checked. A file changed elsewhere reloads, or the editor asks whether to keep your changes or take the disk's.

## Languages

The file's extension decides how it is edited. Clojure files (`.clj`, `.cljs`, `.cljc`, `.edn`, `.bb`) get Clojure colours, indentation and structural editing. Markdown files (`.md`) get Markdown colours, and Enter continues a list. Any other file is plain text. The status bar names the language. Structural editing works in Clojure files only, and evaluation in `.clj`, `.cljc` and `.cljs` files.

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

The settings file is `~/.config/bareedit/config.edn`, one for every project. **Open settings** shows every setting with its default value as a comment.

- **`:preset`** has light and dark colours, and the system setting picks between them.
- **`:ui-font-size`** scales the whole interface, keeping each part's size relative to the others. Without it, the browser's default is used, normally 16.
- **`:font-family`** and **`:font-size`** set the editor and the results column, and do not follow `:ui-font-size`.
- **`:ruler`** is a column from 40 to 200, or `false`. Long lines still wrap at the window's edge.
- **`:syntax`** overrides the colours `:comment`, `:keyword`, `:string`, `:constant`, `:function`, `:type` and `:tag`, each a CSS colour, under `:light` and `:dark` apart.
- A wrong value or an unknown key is ignored, and a warning says so. After editing the file in another editor, reload the page (`Mod-r`).
