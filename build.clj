(ns build
  "Builds the BareEdit jar for Clojars, with `clojure -T:build jar :version '\"0.2.0\"'`.
  `bb jar` runs it after the release build of the browser bundle."
  (:require [clojure.tools.build.api :as b]))

(def lib
  "The Maven coordinate of the jar."
  'com.github.avanelsas/bareedit)

(def class-dir
  "The folder the jar is made from."
  "target/classes")

(def repo-url
  "The repository, for the pom."
  "https://github.com/avanelsas/bareedit")

(defn jar-file
  "The path of the jar for `version`."
  [version]
  (format "target/bareedit-%s.jar" version))

(defn- write-pom!
  "Writes the pom for `version` into the class folder. The jar has no dependencies, because
  the server uses only what Babashka has built in."
  [version]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (b/create-basis {:root nil :project {:deps {} :paths []}})
                :scm       {:url                 repo-url
                            :connection          "scm:git:https://github.com/avanelsas/bareedit.git"
                            :developerConnection "scm:git:ssh://git@github.com/avanelsas/bareedit.git"
                            :tag                 (str "v" version)}
                :pom-data  [[:description "A small, local, browser-based editor for Clojure."]
                            [:url repo-url]
                            [:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/license/mit"]]]]}))

(defn- copy-files!
  "Copies the server code, the page, the release bundle and `version` into the class folder."
  [version]
  (b/copy-dir {:src-dirs ["src/server" "src/shared"] :target-dir class-dir})
  (b/copy-file {:src "resources/public/index.html" :target (str class-dir "/public/index.html")})
  (b/copy-file {:src "resources/public/js/main.js" :target (str class-dir "/public/js/main.js")})
  (b/write-file {:path (str class-dir "/bare_edit/version.txt") :string version}))

(defn jar
  "Builds the jar for `:version` into `target`, from the bundle in `resources/public/js`.
  The bundle must be a release build, so run `bb jar`, which makes one first."
  [{:keys [version]}]
  (when-not (string? version)
    (throw (ex-info "Give the version as a string, such as :version '\"0.2.0\"'." {})))
  (b/delete {:path "target"})
  (write-pom! version)
  (copy-files! version)
  (b/jar {:class-dir class-dir :jar-file (jar-file version)})
  (println "Built" (jar-file version)))
