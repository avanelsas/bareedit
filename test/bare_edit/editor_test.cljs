(ns bare-edit.editor-test
  "Tests the editor's commands on editor states, with no view."
  (:require ["@codemirror/language" :refer [syntaxTree]]
            [bare-edit.editor :as editor]
            [bare-edit.keys :as keys]
            [bare-edit.model :as model]
            [bare-edit.protocol :as p]
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

(deftest every-editor-command-works-on-the-text
  (doseq [{:keys [id scope]} steps/commands :when (editor/command? id)]
    (is (= :editor scope) (str id " runs on the editor but its keys work everywhere"))))

(defn- form-text
  "The `:code` that `f` finds in `text`, with the cursor where `|` is."
  [f text]
  (:code (f (at text))))

(deftest forms-at-the-cursor
  (testing "the form that ends at the cursor, or else the innermost form around it"
    (are [text want] (= want (form-text editor/form-at text))
      "(+ 1 (* 2 3)|)"   "(* 2 3)"
      "(+ 1 (* 2| 3))"   "2"
      "(+ 1 (* |2 3))"   "2"
      "(a b)|\n(c d)"     "(a b)"
      "(inc |)"           "(inc )"))
  (testing "the top-level form"
    (are [text want] (= want (form-text editor/top-level-form-at text))
      "(defn f [x]\n  (* x |2))\n(g)" "(defn f [x]\n  (* x 2))"
      "(a)\n(b |c)"                     "(b c)"))
  (testing "between forms there is none"
    (is (nil? (editor/top-level-form-at (at "(a)\n\n|\n(b)")))))
  (testing "in a rich comment, the form inside it"
    (are [text want] (= want (form-text editor/top-level-form-at text))
      "(comment\n  (start {:port |8080})\n  (stop))"   "(start {:port 8080})"
      "(comment\n  (start)\n  (stop)|)"               "(stop)"
      "(comment\n  (->> (load) (filter :ad|min) count))" "(->> (load) (filter :admin) count)"
      "(comment\n  x|)"                                 "x"
      "(comm|ent\n  (start))"                           "(comment\n  (start))"
      "|(comment\n  (start))"                           "(comment\n  (start))"
      "(comment\n  (start))|"                           "(comment\n  (start))"
      "(comment-helper\n  (st|art))"                    "(comment-helper\n  (start))"
      "(foo\n  (comment (a|)))"                          "(foo\n  (comment (a)))"))
  (testing "between the forms of a rich comment there is none"
    (are [text] (nil? (editor/top-level-form-at (at text)))
      "(comment\n  (start)\n|\n  (stop))"
      "(comment\n  ;; a no|te\n  (stop))"
      "(comment\n  #_(o|ld)\n  (stop))"))
  (testing "a form in a rich comment knows its own line"
    (is (= 3 (:line (editor/top-level-form-at (at "(comment\n  (start)\n  (st|op))"))))))
  (testing "a form knows the line it starts on"
    (is (= 2 (:line (editor/top-level-form-at (at "(a)\n(b |c)")))))))

(deftest symbols-at-the-cursor
  (are [text want] (= want (editor/symbol-at (at text)))
    "(ma|p inc xs)"     "map"
    "(map| inc xs)"     "map"
    "(str/jo|in xs)"    "str/join"
    "(f :ke|y)"         nil
    "(f \"te|xt\")"     nil))

(deftest highlighting-text
  (let [pieces (editor/highlighted "{:a \"b\" :c 1}")]
    (testing "the pieces join to the text"
      (is (= "{:a \"b\" :c 1}" (apply str (map first pieces)))))
    (testing "keywords, strings and numbers get a class, and the rest none"
      (is (= ":a\"b\":c1" (apply str (keep (fn [[p cls]] (when cls p)) pieces)))))))

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

(deftest completion-options
  (is (= [{:label "map" :type "function" :detail "clojure.core"}
          {:label "when" :type "keyword" :detail "clojure.core"}
          {:label "my-var" :type "variable"}
          {:label "odd" :type "text"}]
         (js->clj (editor/completion-options [{:candidate "map" :type "function" :ns "clojure.core"}
                                              {:candidate "when" :type "macro" :ns "clojure.core"}
                                              {:candidate "my-var" :type "var"}
                                              {:candidate "odd" :type "unknown"}])
                  :keywordize-keys true))))

(deftest syntax-colours-come-from-the-theme
  (doseq [[_ token] editor/syntax-colours]
    (is (contains? p/syntax-tokens token) (str token " is one of the theme's syntax tokens"))))

(deftest a-defined-name-has-its-own-colour
  (let [classes (into {} (editor/highlighted "(defn foo [x] (inc x))"))]
    (is (some? (classes "foo")))
    (is (not= (classes "defn") (classes "foo")))
    (is (= (classes "defn") (classes "inc")) "a call is coloured like defn")))

(deftest same-text
  (let [s (editor/new-state "(ns a)")]
    (are [text same?] (= same? (editor/same-text? s text))
      "(ns a)"  true
      "(ns b)"  false
      "(ns a) " false
      ""        false)
    (testing "the answer for a state and a text stays the same when asked again"
      (is (editor/same-text? s "(ns a)"))
      (is (not (editor/same-text? s "(ns b)"))))))

(deftest each-language-has-its-parser
  (let [top (fn [state] (.. (syntaxTree state) -topNode -name))]
    (is (= "Program" (top (editor/new-state "(ns a)"))) "Clojure is the default")
    (is (= "Document" (top (editor/new-state "# Title" :markdown))))
    (is (= "Document" (top (editor/new-state "<p>hi</p>" :html))))
    (is (= "StyleSheet" (top (editor/new-state "a { color: red }" :css))))
    (is (= "JsonText" (top (editor/new-state "{\"a\": 1}" :json))))
    (is (= "Script" (top (editor/new-state "const a = 1" :javascript))))
    (is (not= "Program" (top (editor/new-state "(ns a)" :text))) "plain text is not parsed as Clojure")))

(deftest every-language-has-its-extensions
  (doseq [language (conj (set (vals model/languages)) :text)]
    (is (some? (get editor/language-extensions language)) language)))

(defn- selected
  "The text and the selection of the state after the transaction `tr`, as `[text from to]`."
  [^js tr]
  (let [^js s (.-state tr)]
    [(editor/text s) (.. s -selection -main -from) (.. s -selection -main -to)]))

(deftest finding-in-the-file
  (let [s (at "a |b a b")
        q {:text "a"}]
    (testing "next and previous wrap around"
      (is (= ["a b a b" 4 5] (selected (editor/next-match s q))))
      (is (= ["a b a b" 0 1] (selected (editor/previous-match s q))))
      (is (= ["a b a b" 0 1] (selected (editor/next-match (.-state (editor/next-match s q)) q)))))
    (is (= {:at nil :count 2} (editor/match-position s q)))
    (is (= {:at 1 :count 2} (editor/match-position (.-state (editor/next-match s q)) q)))
    (is (nil? (editor/next-match (at "|abc") {:text "x"})))
    (is (nil? (editor/next-match (at "|abc") nil))))
  (testing "the editor marks the query it was given, apart from the commands"
    (let [^js s (.-state (.update (at "|a") #js {:effects (editor/find-effect {:text "a"})}))]
      (is (= {:text "a"} (editor/find-of s))))))

(deftest replacing-in-the-file
  (let [s (at "|a b a")
        q {:text "a"}]
    (testing "replace selects the next match first, then replaces it and selects the next"
      (let [s1 (.-state (editor/replace-match s q "xy"))]
        (is (= ["a b a" 0 1] (selected (editor/replace-match s q "xy"))))
        (is (= ["xy b a" 5 6] (selected (editor/replace-match s1 q "xy"))))))
    (is (= "xy b xy" (editor/text (.-state (editor/replace-all s q "xy")))))
    (testing "a regular expression replaces with its groups"
      (is (= "[f] [g]" (editor/text (.-state (editor/replace-all (at "|(f) (g)")
                                                                 {:text "\\((\\w)\\)" :regexp true}
                                                                 "[$1]"))))))))
