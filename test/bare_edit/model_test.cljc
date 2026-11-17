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
