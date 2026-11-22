(ns bare-edit.model-test
  "Tests the pure functions over the editor's files.
  They build the file tree, apply the rule for files changed on disk, read the code of a
  file and find the project file of a definition."
  (:require [bare-edit.model :as model]
            [clojure.test :refer [deftest is are]]))

(deftest tree-items
  (is (= [{:id "src" :label "src"
           :children [{:id "src/foo" :label "foo"
                       :children [{:id "src/foo/core.clj" :label "core.clj"}]}
                      {:id "src/a.clj" :label "a.clj"}]}
          {:id "README.md" :label "README.md"}]
         (model/tree-items ["README.md" "src/a.clj" "src/foo/core.clj"]))))

(deftest the-tree-orders-each-folder
  (is (= ["Docs" "src" "apple.md" "file2.txt" "file10.txt" "README.md"]
         (map :label (model/tree-items ["file10.txt" "src/a.clj" "README.md" "Docs/x.md"
                                        "apple.md" "file2.txt"])))))

(deftest name-order
  (are [names] (= names (sort model/name-order (reverse names)))
    ;; case is ignored
    ["apple.md" "bb.edn" "README.md" "Zebra.md"]
    ;; runs of digits compare as numbers, at any length
    ["file2.txt" "file10.txt" "file99999999999999999999.txt" "file100000000000000000000.txt"]
    ;; other characters keep their code order: a dot before digits, `_` after them
    [".github" ".gitignore" "1.txt" "_notes.md" "notes.md"]
    ;; a shorter name that the longer one starts with comes first
    ["core" "core.clj" "core_test.clj"]
    ;; names equal apart from case or leading zeros keep one order
    ["README.md" "readme.md"]
    ["file007" "file7"]))

(deftest disk-action
  ;; One rule, whoever changed the file.
  (are [unsaved? known now action] (= action (model/disk-action unsaved? known now))
    false "v1"    "v1"    :none
    true  "v1"    "v1"    :none
    false "v1"    "v2"    :reload
    true  "v1"    "v2"    :ask
    false "v1"    :absent :close
    true  "v1"    :absent :mark-deleted
    true  :absent :absent :none))

(def paths
  "Paths for the ranking tests."
  ["src/foo/bar_baz.cljs" "src/core.cljs" "src/app/core.cljs" "test/core_test.cljs"
   "src/encore.clj" "src/score/x.clj" "README.md"])

(deftest rank-paths
  (are [query want] (= want (model/rank-paths paths query 10))
    ;; a word matches as typed, or with . as / and - as _
    "foo.bar-baz" ["src/foo/bar_baz.cljs"]
    "bar-baz"     ["src/foo/bar_baz.cljs"]
    ;; tiers on the file name: equal, then starts with, then contains, then elsewhere;
    ;; within a tier, shorter paths first
    "core"        ["src/core.cljs" "src/app/core.cljs" "test/core_test.cljs" "src/encore.clj"
                   "src/score/x.clj"]
    ;; every word must match, and the last word ranks
    "test core"   ["test/core_test.cljs"]
    "CORE"        ["src/core.cljs" "src/app/core.cljs" "test/core_test.cljs" "src/encore.clj"
                   "src/score/x.clj"]
    "zzz"         [])
  (is (= ["src/core.cljs" "src/app/core.cljs"] (model/rank-paths paths "core" 2)))
  (is (= ["src/bare_edit/steps.cljs" "a/bare_edit/steps/x.clj"]
         (model/rank-paths ["a/bare_edit/steps/x.clj" "src/bare_edit/steps.cljs"] "bare-edit.steps" 5))
      "a namespace ranks its own file first, by the last part of its name")
  (is (= ["README.md" "src/core.cljs" "src/encore.clj"] (model/rank-paths paths "" 3))
      "an empty query lists the shortest paths")
  (is (= ["x/alfa.clj" "x/Beta.clj" "x/f2__.clj" "x/f10_.clj"]
         (model/rank-paths ["x/f10_.clj" "x/Beta.clj" "x/f2__.clj" "x/alfa.clj"] "" 4))
      "equal ranks keep the order of the file tree"))

(deftest path-problem
  (are [path problem] (= problem (model/path-problem path))
    "src/a.clj"        nil
    "src/new/dir/b.clj" nil
    ""                 "Enter a path."
    "   "              "Enter a path."
    "/etc/passwd"      "Use a path inside the project."
    "../x.clj"         "Use a path inside the project."
    "src/../../x.clj"  "Use a path inside the project."
    "src/"             "Add a file name."
    "src/."            "Add a file name."
    "./src/a.clj"      nil
    "src//./a.clj"     nil))

(deftest normalize-path
  (are [path want] (= want (model/normalize-path path))
    "src/a.clj"        "src/a.clj"
    " ./src/a.clj "    "src/a.clj"
    "src//./a.clj"     "src/a.clj"))

(deftest folder-of
  (are [path folder] (= folder (model/folder-of path))
    "src/demo/core.clj" "src/demo/"
    "README.md"         ""
    nil                 ""))

(deftest namespaces-of-files
  (are [text want] (= want (model/ns-of text))
    "(ns a.core\n  (:require [b]))" "a.core"
    "(ns ^:no-doc a.b)"             "a.b"
    ";; a comment\n(ns a)"         "a"
    "(defn f [])"                   nil
    ""                              nil))

(deftest form-heads
  (is (= "(defn f [x]" (model/form-head "(defn f [x]\n  x)" 50)))
  (is (= "(abc…" (model/form-head "(abcdef)" 4)))
  (is (= "" (model/form-head nil 10))))

(deftest language-of
  (are [path language] (= language (model/language-of path))
    "src/a.clj"        :clojure
    "src/a.cljs"       :clojure
    "deps.edn"         :clojure
    :settings          :clojure
    "README.md"        :markdown
    "docs/NOTES.MD"    :markdown
    "a.markdown"       :markdown
    "public/index.html" :html
    "page.htm"         :html
    "app.css"          :css
    "package.json"     :json
    "src/main.js"      :javascript
    "view.jsx"         :javascript
    "lib.mjs"          :javascript
    "notes.txt"        :text
    "Makefile"         :text
    ".gitignore"       :text))

(deftest definitions
  (let [paths ["src/a/core.clj" "test/a/core.clj" "src/b.clj" "b.clj" "dev/user.clj"]]
    (are [file found] (= found (model/definition file paths))
      "src/a/core.clj"                         {:path "src/a/core.clj"}
      "src/b.clj"                              {:path "src/b.clj"}
      "user.clj"                               {:path "dev/user.clj"}
      "/Users/me/proj/src/b.clj"               {:path "src/b.clj"}
      "/Users/me/proj/b.clj"                   {:path "b.clj"}
      "/Users/me/other/c.clj"                  nil
      "a/core.clj"                             {:ambiguous ["src/a/core.clj" "test/a/core.clj"]}
      "b.clj"                                  {:path "b.clj"}
      "clojure/string.clj"                     nil
      "NO_SOURCE_PATH"                         nil
      ""                                       nil
      nil                                      nil)))

(deftest source-paths
  (are [file path] (= path (model/source-path file))
    "file:/Users/me/my%20proj/a.clj"                   "/Users/me/my proj/a.clj"
    "jar:file:/m2/clojure.jar!/clojure/string.clj"     "clojure/string.clj"
    "clojure/string.clj"                               "clojure/string.clj"
    nil                                                nil))
