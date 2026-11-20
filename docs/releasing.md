# Releasing BareEdit

A release is a version tag on `main`. The tag starts a workflow that runs the checks, builds the jar and deploys it to Clojars. Users then install that version with bbin.

## Once

- A Clojars deploy token for the group `com.github.avanelsas`.
- The repository secrets `CLOJARS_USERNAME` (the Clojars user) and `CLOJARS_PASSWORD` (the deploy token).

## Each release

1. **Choose the version.** Versions follow [Semantic Versioning](https://semver.org). Before 1.0, a release with new features raises the minor number (`0.2.0` to `0.3.0`), and a release with fixes only raises the patch number (`0.2.0` to `0.2.1`).

2. **Prepare the release on a branch** named `release-X.Y.Z`, from an up-to-date `main`:
   - In `CHANGELOG.md`, move the lines under `## [Unreleased]` to a new section `## [X.Y.Z] - YYYY-MM-DD`, with a sentence that sums up the release. Keep `## [Unreleased]` empty above it.
   - At the end of `CHANGELOG.md`, point `[Unreleased]` at `vX.Y.Z...HEAD` and add the link `[X.Y.Z]: https://github.com/avanelsas/bareedit/releases/tag/vX.Y.Z`.
   - In `README.md`, put the new version in the install command.

3. **Check the jar locally.** Run the five checks in `CLAUDE.md`, then `bb jar`. The jar is `target/bareedit-0.0.0-SNAPSHOT.jar`, because there is no tag yet. To try it as a user would, copy the jar and `target/classes/META-INF/maven/com.github.avanelsas/bareedit/pom.xml` into `~/.m2/repository/com/github/avanelsas/bareedit/0.0.0-SNAPSHOT/` (the pom as `bareedit-0.0.0-SNAPSHOT.pom`), then:

   ```sh
   bbin install com.github.avanelsas/bareedit --mvn/version 0.0.0-SNAPSHOT
   bareedit --version
   ```

   Afterwards, remove that folder from `~/.m2` and install the released version again.

4. **Merge the branch** through a pull request titled `Release X.Y.Z`.

5. **Tag `main`** and push the tag:

   ```sh
   git switch main
   git pull
   git tag -a vX.Y.Z -m "BareEdit X.Y.Z"
   git push origin vX.Y.Z
   ```

6. **Watch the release workflow** in the Actions tab. It runs the checks, builds the jar with the version from the tag, and deploys it to Clojars. The version shows on https://clojars.org/com.github.avanelsas/bareedit when it is done.

7. **Publish the GitHub release** with the changelog section of the version as its notes:

   ```sh
   gh release create vX.Y.Z --title "BareEdit X.Y.Z" --notes-file notes.md
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

- `bb jar` makes the release build of the browser bundle, then runs `clojure -T:build jar` with the version. The version is the tag on `HEAD` without its `v`, or `0.0.0-SNAPSHOT` when there is none.
- `build.clj` makes the jar from `src/server`, `src/shared`, `resources/public/index.html`, the bundle `resources/public/js/main.js` and `bare_edit/version.txt`, which `bareedit --version` reads. The jar has no dependencies.
- bbin runs the namespace `avanelsas.bareedit`, after the group and the artifact, and names the command `bareedit`, after the artifact.
- bbin 0.2.5 has no `upgrade` command. A user upgrades with the install command and the new version, which replaces the old one.
