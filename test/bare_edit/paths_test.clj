(ns bare-edit.paths-test
  "Tests that no path leads outside the project, through `..`, a symlink or a sibling folder."
  (:require [bare-edit.paths :as paths]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest are is]]))

(defn- project
  "A temporary project folder `proj` with `src/a.clj`, next to a folder `proj-other`.
  In the project, the symlink `escape` leads to the sibling and `inner` to `src`. Returns
  `{:base :root}`, the temporary folder and the project folder."
  []
  (let [base (fs/create-temp-dir)
        root (fs/create-dirs (fs/path base "proj"))
        out  (fs/create-dirs (fs/path base "proj-other"))]
    (fs/create-dirs (fs/path root "src"))
    (spit (str (fs/path root "src" "a.clj")) "(ns a)")
    (spit (str (fs/path out "secret.txt")) "secret")
    (fs/create-sym-link (fs/path root "escape") out)
    (fs/create-sym-link (fs/path root "inner") (fs/path root "src"))
    {:base base :root root}))

(deftest resolve-in
  (let [{:keys [root]} (project)]
    (are [rel inside?] (= inside? (some? (paths/resolve-in root rel)))
      "src/a.clj"                 true
      "src/new/dir/b.clj"         true    ; a file that does not exist yet
      "inner/a.clj"               true    ; a symlink that stays inside
      "src/../src/a.clj"          true
      ""                          false
      "   "                       false
      nil                         false
      "."                         false   ; the project folder itself is not a file
      "../proj-other/secret.txt"  false   ; a sibling whose name starts with the root's
      "src/../../outside.clj"     false
      "escape/secret.txt"         false   ; a symlink that leads outside
      "/etc/passwd"               false)))

(deftest relative
  (let [{:keys [root]} (project)]
    (is (= "src/a.clj" (paths/relative root (paths/resolve-in root "src/a.clj"))))))
