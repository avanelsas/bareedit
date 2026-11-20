(ns build
  "Builds, installs and deploys the BareEdit jar, with `clojure -T:build jar`, `install` or
  `deploy`. The version comes from the tag on `HEAD`. `bb jar` makes the release bundle first."
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib
  "The Maven coordinate of the jar."
  'com.github.avanelsas/bareedit)

(def class-dir
  "The folder the jar is made from."
  "target/classes")

(def repo-url
  "The repository, for the pom."
  "https://github.com/avanelsas/bareedit")

(def bundle
  "The browser bundle the jar serves."
  "resources/public/js/main.js")

(def contents
  "What goes in the jar, as pairs of a source in the repository and its place in the jar.
  A folder is copied with all its files."
  [["src/server" ""]
   ["src/shared" ""]
   ["resources/public/index.html" "public/index.html"]
   [bundle "public/js/main.js"]])

(def snapshot
  "The version of a build from a commit with no version tag."
  "0.0.0-SNAPSHOT")

(defn- no-deps-basis
  "A basis with no dependencies and no Clojure, for the pom and the install."
  []
  (b/create-basis {:root nil :project {:deps {} :paths []}}))

(defn tag->version
  "The version of the tag `tag`, such as `\"0.2.0\"` for `\"v0.2.0\"`, or [[snapshot]]
  when `tag` is `nil`."
  [tag]
  (if tag (subs tag 1) snapshot))

(defn- head-tag
  "The version tag on `HEAD`, such as `\"v0.2.0\"`, or `nil` when there is none."
  []
  (some-> (b/git-process {:git-args "tag --points-at HEAD --list v[0-9]*"})
          str/split-lines
          first
          not-empty))

(defn- scm-tag
  "What the pom names as the source of `version`: its tag, or the commit for [[snapshot]]."
  [version]
  (if (= snapshot version)
    (b/git-process {:git-args "rev-parse HEAD"})
    (str "v" version)))

(defn- jar-file
  "The path of the jar for `version`."
  [version]
  (format "target/bareedit-%s.jar" version))

(defn- dev-bundle?
  "True when the bundle text `text` is a development build, which loads files the jar
  does not hold."
  [text]
  (str/includes? text "cljs-runtime"))

(defn- check-bundle!
  "Stops the build when [[bundle]] is missing or a development build."
  []
  (when-not (.exists (java.io.File. ^String bundle))
    (throw (ex-info (str bundle " is missing. Run bb jar, which builds it.") {})))
  (when (dev-bundle? (slurp bundle))
    (throw (ex-info (str bundle " is a development build. Run bb jar, which makes a release build.") {}))))

(defn- write-pom!
  "Writes the pom for `version` into the class folder. The jar has no dependencies, because
  the server uses only what Babashka has built in."
  [version]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (no-deps-basis)
                :scm       {:url                 repo-url
                            :connection          "scm:git:https://github.com/avanelsas/bareedit.git"
                            :developerConnection "scm:git:ssh://git@github.com/avanelsas/bareedit.git"
                            :tag                 (scm-tag version)}
                :pom-data  [[:description "A small, local, browser-based editor for Clojure."]
                            [:url repo-url]
                            [:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/license/mit"]]]]}))

(defn- copy!
  "Copies the source `from` to the place `to` in the class folder, see [[contents]]."
  [[from to]]
  (let [target (str class-dir "/" to)]
    (if (.isDirectory (java.io.File. ^String from))
      (b/copy-dir {:src-dirs [from] :target-dir target})
      (b/copy-file {:src from :target target}))))

(defn- build-jar!
  "Builds the jar for `version` into `target` and returns its path."
  [version]
  (check-bundle!)
  (b/delete {:path "target"})
  (write-pom! version)
  (run! copy! contents)
  (b/jar {:class-dir class-dir :jar-file (jar-file version)})
  (jar-file version))

(defn jar
  "Builds the jar into `target`, for the version of the tag on `HEAD`."
  [_]
  (println "Built" (build-jar! (tag->version (head-tag)))))

(defn install
  "Builds the jar and installs it in the local Maven repository, as a user's bbin would
  fetch it."
  [_]
  (let [version (tag->version (head-tag))]
    (b/install {:basis     (no-deps-basis)
                :lib       lib
                :version   version
                :jar-file  (build-jar! version)
                :class-dir class-dir})
    (println "Installed" lib version)))

(defn deploy
  "Builds the jar and deploys it to Clojars, with the credentials in `CLOJARS_USERNAME` and
  `CLOJARS_PASSWORD`. It refuses a build with no version tag on `HEAD`."
  [_]
  (let [version (tag->version (head-tag))]
    (when (= snapshot version)
      (throw (ex-info "HEAD has no version tag, so there is nothing to deploy." {})))
    (dd/deploy {:installer :remote
                :artifact  (build-jar! version)
                :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})))
