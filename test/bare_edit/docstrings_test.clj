(ns bare-edit.docstrings-test
  "Tests that the docstring checker finds each kind of problem and passes good docstrings."
  (:require [bare-edit.docstrings :as docstrings]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

(defn- problems
  "The problems the checker reports for a source file holding `source`."
  [source]
  (let [file (fs/file (fs/create-temp-dir) "checked.clj")]
    (spit file source)
    (mapv :problem (docstrings/file-problems file))))

(deftest good-docstrings-pass
  (is (= [] (problems "(ns a.b
  \"Does one thing well.
  More about it here.\")

(def x
  \"The answer.\"
  42)

(defonce ^{:doc \"The state.\"} state (atom nil))

(defn f
  \"Adds `a` to `b`.

  - A list item may run on,
    indented further.\"
  [a {:keys [c] :as b} & _more]
  (+ a b c))

(defn g
  \"Takes `x`, or `x` and `y`.\"
  ([x] x)
  ([x y] (+ x y)))

(deftest no-docstring-needed (is true))"))))

(deftest each-problem-is-found
  (is (= ["no docstring"] (problems "(ns a)")))
  (is (= ["no docstring"] (problems "(ns a \"A.\") (def x \"just a value\")")))
  (is (= ["leading or trailing whitespace"] (problems "(ns a \"A. \")")))
  (is (= ["first line is not one capitalized sentence: does a thing"]
         (problems "(ns a \"does a thing\")")))
  (is (= ["continuation line indented 3, want 2: More."]
         (problems "(ns a\n  \"A.\n   More.\")")))
  (is (= ["refers to a document outside the repository: D21"]
         (problems "(ns a \"Follows D21.\")")))
  (is (= ["argument `b` is not named in backticks"]
         (problems "(ns a \"A.\") (defn f \"Uses `a`.\" [a b] b)")))
  (is (= ["first line runs past column 100"]
         (problems (str "(ns a \"" (apply str (repeat 100 "X")) ".\")")))))
