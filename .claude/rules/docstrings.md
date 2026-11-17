---
paths:
  - "**/*.clj"
  - "**/*.cljs"
  - "**/*.cljc"
---

# Docstrings

Every docstring in this project follows the documentation section of the Clojure Style Guide (https://guide.clojure.style/#documentation). Apply these rules whenever you write or change a definition, and check the docstrings of any file you edit.

Run `bb docstrings` after changing Clojure code. It checks what a program can: a docstring is present, the first line is one sentence, indentation and whitespace, arguments in backticks, and outside references. It exits with 1 on any problem, so CI can run it too. Whether a docstring says what the code does still needs a careful read of the code.

## What gets a docstring

- Every `ns`, and every `def`, `defn`, `defn-`, `defmulti`, `defonce`, `defmacro` and `defprotocol`, public or private, in `src` and `test`. Only `deftest` is exempt, since its name and `testing` strings describe it.
- `defmethod` cannot take a docstring. Its `defmulti` documents what the methods do.
- Use the docstring itself, not `:doc` metadata, wherever the form takes one. A comment above or beside a definition that describes the var belongs in its docstring instead.
- `defonce` takes no docstring (its only arguments are a name and a value), so it gets `:doc` metadata, as in `(defonce ^{:doc "Docstring."} name value)`. The rules below apply to that string as well.

## Where it goes

- In `defn`, `defn-`, `defmulti` and `defmacro`, it comes after the name and before the argument vector. A string after the argument vector is not a docstring, just a value in the body.
- In `def`, it comes between the name and the value: `(def name "Docstring." value)`.
- In `ns`, it comes right after the namespace name.

## How it reads

- The first line is a complete, capitalized sentence that sums up the var on its own, because editors show only that line. It ends with a period and fits on that first line.
- Every sentence is well-formed English. Each starts with a capital letter and ends with punctuation, and sentences are separated by a single space.
- Name every positional argument of a function in backticks, for example "Reads `path` under `root`." When an argument is a destructured map, name the keys it uses.
- Put var references in backticks. Use `[[name]]` or `[[ns/name]]` when the reference should be a link, as in "See also [[resolve-in]]."
- Use Markdown for code, keywords and literals, as in `` `:absent` `` and `` `{:text :version}` ``.
- A docstring starts and ends with no whitespace.
- Indent the continuation lines of a multi-line docstring by two spaces from the start of the form, which lines them up with the opening quote:

  ```clojure
  (defn save!
    "Writes `text` to `rel` under `root` when the file still has version `expected`.
    Returns `{:version new}`, or `{:conflict current}` when the file changed on disk."
    [root rel text expected]
    ...)
  ```

## What it may refer to

- A docstring stands on its own within this repository. Never refer to documents outside it: no spec or design decision numbers (such as D21 or L14), review decisions, plan stages, or files in parent folders. When a rule comes from such a document, state the rule and its reason in the docstring itself.
- The same holds for code comments, test names and `testing` strings.
