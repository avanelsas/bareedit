# GitHub Pages demo

The code in this folder is used only for the read-only demo of BareEdit on GitHub Pages. It is not part of the editor. The jar and the editor's browser bundle do not include it.

The demo runs the editor's own browser app. A stand-in in the browser takes the place of the server. It shows a snapshot of this repository and refuses every change.

- `src/bare_edit/github_pages_demo.cljs` starts the demo and loads the snapshot.
- `src/bare_edit/github_pages_demo/server.cljs` is the stand-in for the server.
- `src/bare_edit/github_pages_demo/snapshot.clj` makes the snapshot from the files git tracks.
- `test` holds the tests of the stand-in and the snapshot.

`bb github-pages-demo` builds the demo into `target/github-pages-demo`. The workflow `.github/workflows/github-pages-demo.yml` builds and deploys it.
