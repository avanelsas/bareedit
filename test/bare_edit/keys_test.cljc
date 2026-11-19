(ns bare-edit.keys-test
  "The key matcher's test table: what each key event must match, on macOS and elsewhere."
  (:require [bare-edit.keys :as keys]
            [clojure.test :refer [deftest are is]]))

(deftest macos-table
  (are [ev want] (= want (keys/event->key true ev))
    {:key "ß" :code "KeyS" :alt true}                      "Alt-s"
    {:key "‘" :code "BracketRight" :alt true}              "Alt-]"
    {:key "0" :code "Digit0" :ctrl true :shift true}       "Shift-Ctrl-0"
    {:key ")" :code "Digit0" :ctrl true :shift true}       "Shift-Ctrl-0"
    {:key "}" :code "BracketRight" :ctrl true :shift true} "Shift-Ctrl-]"
    {:key "P" :code "KeyP" :meta true :shift true}         "Shift-Meta-p"
    {:key "p" :code "KeyP" :meta true}                     "Meta-p"
    {:key " " :code "Space" :ctrl true}                    "Ctrl-Space"
    {:key "Enter" :code "Enter" :meta true :shift true}    "Shift-Meta-Enter"))

(deftest specs-match-events
  (are [spec ev] (= (keys/spec->key true spec) (keys/event->key true ev))
    "Mod-s"         {:key "s" :code "KeyS" :meta true}
    "Mod-Shift-p"   {:key "P" :code "KeyP" :meta true :shift true}
    "Ctrl-Shift-0"  {:key "0" :code "Digit0" :ctrl true :shift true}
    "Alt-w"         {:key "∑" :code "KeyW" :alt true}
    "F1"            {:key "F1" :code "F1"})
  (is (= "Ctrl-s" (keys/spec->key false "Mod-s"))))

(deftest key-index
  (is (= :palette (:id ((keys/key-index true [{:id :palette :keys ["Mod-Shift-p" "F1"]}]) "F1")))))

(deftest shortcuts-for-x-kbd
  (are [spec want] (= want (keys/shortcut spec))
    "Mod-s"                   "Mod+S"
    "Mod-Shift-Enter"         "Mod+Shift+Enter"
    "Alt-]"                   "Alt+]"
    "Mod-."                   "Mod+."
    "Ctrl-Space"              "Ctrl+Space"
    "Alt-ArrowUp"             "Alt+↑"
    "F2"                      "F2"
    ["Mod-Shift-p" "F1"]      "Mod+Shift+P"
    nil                       nil))
