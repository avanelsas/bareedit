# Releasing BareEdit

A release is a version tag on `main`. The tag starts a workflow that runs the checks, builds the jar and deploys it to Clojars. Users then install that version with bbin.

## Once

- A Clojars deploy token for the group `com.github.avanelsas`.
- The repository secrets `CLOJARS_USERNAME` (the Clojars user) and `CLOJARS_PASSWORD` (the deploy token).

## Each release

1. **Choose the version.** Versions follow [Semantic Versioning](https://semver.org). Before 1.0, a release with new features raises the minor number (`0.2.0` to `0.3.0`), and a release with fixes only raises the patch number (`0.2.0` to `0.2.1`).

2. **Prepare the release on a branch** named `release-X.Y.Z`, from an up-to-date `main`:
   - In `CHANGELOG.md`, move the lines under `## [Unreleased]` to a new section `## [X.Y.Z] - YYYY-MM-DD`, with a sentence that sums up the release. The date is the day of the tag. Correct it before the merge if the tag moves to another day. Keep `## [Unreleased]` empty above it.
   - At the end of `CHANGELOG.md`, point `[Unreleased]` at `vX.Y.Z...HEAD` and add the link `[X.Y.Z]: https://github.com/avanelsas/bareedit/releases/tag/vX.Y.Z`.
   - In `README.md`, put the new version in the install command.

3. **Check the jar locally.** Run the five checks in `CLAUDE.md`, then `bb install`. It builds the jar and installs it in the local Maven repository as `0.0.0-SNAPSHOT`, because there is no tag yet. Try it as a user would, in a folder of its own, so your own `bareedit` stays as it is:

   ```sh
   export BABASHKA_BBIN_BIN_DIR=$(mktemp -d)
   bbin install com.github.avanelsas/bareedit --mvn/version 0.0.0-SNAPSHOT
   "$BABASHKA_BBIN_BIN_DIR/bareedit" --version
   "$BABASHKA_BBIN_BIN_DIR/bareedit"
   unset BABASHKA_BBIN_BIN_DIR
   ```

4. **Merge the branch** through a pull request titled `Release X.Y.Z`.

5. **Tag `main`** and push the tag:

   ```sh
   git switch main
   git pull
   git tag -a vX.Y.Z -m "BareEdit X.Y.Z"
   git push origin vX.Y.Z
   ```

6. **Watch the Release workflow** in the Actions tab. It runs the same checks as CI, then makes the release bundle, builds the jar with the version from the tag, and deploys it to Clojars. The version shows on https://clojars.org/com.github.avanelsas/bareedit when it is done.

7. **Publish the GitHub release,** with the section of the version in `CHANGELOG.md` as its notes:

   ```sh
   awk '/^## \[X.Y.Z\]/ {on=1; next} /^## \[/ {on=0} on' CHANGELOG.md > notes.md
   gh release create vX.Y.Z --title "BareEdit X.Y.Z" --notes-file notes.md
   rm notes.md
   ```

8. **Install it as a user would,** in a new terminal:

   ```sh
   bbin install com.github.avanelsas/bareedit --mvn/version X.Y.Z
   bareedit --version
   ```

## When something goes wrong

- **The workflow fails before the deploy.** Nothing is published. Fix the problem on a branch and merge it. Then delete the tag (`git push origin :refs/tags/vX.Y.Z` and `git tag -d vX.Y.Z`) and tag the new `main`.
- **A published version is broken.** Clojars never replaces a version. Fix the problem and release the next patch version.

## How the pieces fit

- `.github/workflows/release.yml` runs on a tag such as `v0.2.0`. It calls `ci.yml` for the checks, then runs `bb build` and `clojure -T:build deploy`.
- `build.clj` has three functions, `jar`, `install` and `deploy`. Each builds the jar for the version of the tag on `HEAD`, without its `v`, or `0.0.0-SNAPSHOT` when there is none. `deploy` refuses a build with no tag. `bb jar` and `bb install` make the release bundle first, and the build refuses a development bundle.
- The jar holds what `contents` in `build.clj` lists: `src/server`, `src/shared`, the page and the bundle. Its pom has no dependencies. `bareedit --version` reads the version from the jar's `pom.properties`.
- bbin runs the namespace `avanelsas.bareedit`, after the group and the artifact, and names the command `bareedit`, after the artifact.
- bbin 0.2.5 has no `upgrade` command. A user upgrades with the install command and the new version, which replaces the old one.
