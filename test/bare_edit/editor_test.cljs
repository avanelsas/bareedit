(ns bare-edit.editor-test
  "Tests the editor's commands on editor states, with no view."
  (:require [bare-edit.editor :as editor]
            [bare-edit.keys :as keys]
            [bare-edit.steps :as steps]
            [clojure.set]
            [clojure.test :refer [deftest is are testing]]))

(defn- at
  "An editor state holding `text`, with the cursor where `|` is, and the `|` removed."
  [text]
  (let [i (.indexOf text "|")
        s (editor/new-state (str (subs text 0 i) (subs text (inc i))))]
    (.-state (.update s #js {:selection #js {:anchor i}}))))

(defn- after
  "The text, with `|` at the cursor, after the command `id` runs on `text`."
  [text id]
  (let [s  (at text)
        tr (editor/edit s id)
        s2 (if tr (.-state tr) s)
        t  (editor/text s2)
        c  (.. s2 -selection -main -head)]
    (str (subs t 0 c) "|" (subs t c))))

;; kill copies the killed text to the clipboard through the DOM, so it is tested in a browser
(deftest structural-commands
  (are [text id want] (= want (after text id))
    "(a|) b"          :slurp-forward  "(a| b)"
    "a (|b)"          :slurp-backward "(a |b)"
    "(a| b)"          :barf-forward   "(a|) b"
    "(a |b)"          :barf-backward  "a (|b)"
    "(a (|b c))"      :unwrap         "(a |b c)"
    "(defn f [x]\n(inc x)|)" :reindent "(defn f [x]\n  (inc x)|)")
  (testing "a structural command tidies only the lines it changed"
    (is (= "(foo\n  (a| b))\nx   y" (after "(foo\n  (a|) b)\nx   y" :slurp-forward)))))

(deftest selection-commands
  (let [^js s  (at "(a |bc d)")
        ^js s2 (.-state (editor/edit s :expand-selection))]
    (is (= "bc" (.sliceDoc s2 (.. s2 -selection -main -from) (.. s2 -selection -main -to))))
    (is (= [3 3] (let [^js s3 (.-state (editor/edit s2 :shrink-selection))]
                   [(.. s3 -selection -main -from) (.. s3 -selection -main -to)])))))

(deftest every-editor-command-is-known-to-the-editor
  (doseq [{:keys [id scope]} steps/commands :when (= :editor scope)]
    (is (editor/command? id) (str id " has no editor command"))))

(deftest no-key-has-two-sources
  (doseq [mac? [true false]]
    (let [command-keys (set (keys (keys/key-index mac? steps/commands)))
          layer-keys   (set (keep #(keys/spec->key mac? %)
                                  (editor/text-layer-keys mac?)))]
      (is (empty? (clojure.set/difference (clojure.set/intersection command-keys layer-keys)
                                          (set (map #(keys/spec->key mac? %) editor/passes-through))))
          (str "keys bound twice" (when mac? " on macOS"))))))

(deftest a-command-that-changes-nothing-gives-no-transaction
  (is (nil? (editor/edit (at "|(a) b") :slurp-forward)))
  (is (nil? (editor/edit (at "(a) |b") :reindent))))
