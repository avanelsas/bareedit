(ns bare-edit.model-test
  "Tests the file tree and the rule for files changed on disk."
  (:require [bare-edit.model :as model]
            [clojure.test :refer [deftest is are]]))

(deftest tree-items
  (is (= [{:id "src" :label "src"
           :children [{:id "src/foo" :label "foo"
                       :children [{:id "src/foo/core.clj" :label "core.clj"}]}
                      {:id "src/a.clj" :label "a.clj"}]}
          {:id "README.md" :label "README.md"}]
         (model/tree-items ["README.md" "src/a.clj" "src/foo/core.clj"]))))

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
      "an empty query lists the shortest paths"))

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
