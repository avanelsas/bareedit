# Developing BareEdit

How to build BareEdit from a clone and work on it. How to use it is in the [README](../README.md).

## Run from a clone

You need [Babashka](https://babashka.org), Java, the [Clojure CLI](https://clojure.org/guides/install_clojure) and Node with npm.

```sh
git clone https://github.com/avanelsas/bareedit.git
cd bareedit
npm ci
bb build
```

Then add an alias to `~/.zshrc` or `~/.bashrc`: `alias bareedit='bb --config /path/to/bareedit/bb.edn start'`.

## Tasks

| Task | Command |
|---|---|
| Server and shared tests (bb) | `bb test` |
| Browser and shared tests (Node) | `bb cljs-test` |
| Lint | `clj-kondo --lint src test dev github-pages-demo` |
| Check docstrings | `bb docstrings` |
| Build the browser bundle | `bb build` |
| Build the jar, or install it in `~/.m2` | `bb jar`, `bb install` |
| Build the read-only demo for GitHub Pages into `target/github-pages-demo` | `bb github-pages-demo` |

## Layout

- `src/server` holds the Babashka server.
- `src/app` holds the browser app.
- `src/shared` holds the protocol that both use.
- `test` holds the tests.
- `github-pages-demo` holds the read-only demo. It runs the browser app with a stand-in for the server, and it is not part of the editor.

## Releases

How to make a release is in [releasing.md](releasing.md).
