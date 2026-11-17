(ns bare-edit.clojure-mode-test
  "Checks that clojure-mode, which comes as ClojureScript source, builds as plain functions.
  Its commands and formatter must be callable from our own code."
  (:require [clojure.test :refer [deftest is]]
            [nextjournal.clojure-mode :as cm-clj]
            [nextjournal.clojure-mode.commands :as commands]
            [nextjournal.clojure-mode.extensions.close-brackets :as close-brackets]
            [nextjournal.clojure-mode.extensions.formatting :as formatting]
            [nextjournal.clojure-mode.extensions.selection-history :as sel-history]))

(deftest parts-are-functions
  (is (fn? cm-clj/syntax))
  (is (fn? close-brackets/extension))
  (is (fn? sel-history/extension))
  (is (fn? formatting/format-transaction))
  (is (every? fn? [commands/slurp-forward commands/barf-forward commands/unwrap commands/kill])))
