(ns bare-edit.page-test
  "Tests the page as a value, rendered from a state with no browser.
  Each control is checked by the event its handler dispatches."
  (:require [bare-edit.editor :as editor]
            [bare-edit.page :as page]
            [bare-edit.steps :as steps]
            [clojure.test :refer [deftest is testing]]))

(defn- elements
  "Every element in the hiccup `h` whose tag is `tag`, as `[tag props & children]` vectors."
  [h tag]
  (filter #(and (vector? %) (= tag (first %)))
          (tree-seq #(or (vector? %) (seq? %)) #(if (vector? %) (rest %) (seq %)) h)))

(defn- text
  "All the text inside the hiccup `h`, joined."
  [h]
  (apply str (filter string? (tree-seq #(or (vector? %) (seq? %)) seq h))))

(defn- editor-style
  "The style of the editor's element in the hiccup `h`."
  [h]
  (some #(when (= "editor" (:id (second %))) (:style (second %))) (elements h :div)))

(defn- render
  "The page for the state `s`, with a `dispatch` that records events in `events`."
  [s events]
  (page/page s #(swap! events conj %)))

(def state
  "A state with `src/a.clj` open, loaded and edited, and `src/b.clj` open but still loading."
  (-> steps/initial-state
      (assoc :conn :open :paths ["src/a.clj" "src/b.clj"] :tabs [1 2] :active 1)
      (assoc-in [:buffers 1] {:id 1 :path "src/a.clj" :disk-text "(ns a)" :disk-version "v1"
                              :state (editor/new-state "(ns a) 1")})
      (assoc-in [:buffers 2] {:id 2 :path "src/b.clj" :loading? true})))

(deftest tabs-and-tree
  (let [events (atom [])
        h      (render state events)]
    (is (= ["a.clj ●" "b.clj"] (map text (elements h :x-tab))))
    (testing "selecting a file in the tree opens it"
      ((:on-x-tree-select (second (first (elements h :x-tree)))) #js {:detail #js {:id "src/b.clj"}})
      (is (= [[:open-file "src/b.clj"]] @events)))
    (testing "the editor shows the active buffer, and hides while the active one loads"
      (is (= "" (editor-style h)))
      (is (= "display:none" (editor-style (render (assoc state :active 2) events)))))))

(deftest question-dialogs
  (testing "no question, no dialog"
    (is (empty? (elements (render state (atom [])) :x-modal))))
  (testing "the close question names the file, and each button answers it"
    (let [events (atom [])
          h      (render (steps/ask state {:kind :close :buffer 1}) events)]
      (is (re-find #"Save a\.clj\?" (text (elements h :x-modal))))
      (doseq [b (elements (elements h :x-modal) :x-button)] ((:on-click (second b)) nil))
      (is (= [[:answer :cancel] [:answer :discard] [:answer :save]] @events))))
  (testing "only the first question shows"
    (let [s (-> state
                (steps/ask {:kind :disk-change :buffer 1 :disk {:text "x" :version "v2"}})
                (steps/ask {:kind :close :buffer 1}))
          h (render s (atom []))]
      (is (= 1 (count (elements h :x-modal))))
      (is (= ["Take disk" "Keep mine"] (map text (elements (elements h :x-modal) :x-button)))))))

(defn- fake-event
  "A DOM event stand-in with `detail`, which records a call to `preventDefault` in `prevented`."
  [detail prevented]
  #js {:detail (clj->js detail) :preventDefault #(reset! prevented true)})

(deftest the-palette-is-a-view-of-the-state
  (let [events    (atom [])
        prevented (atom false)
        el        (first (elements (render (assoc state :palette {:kind :commands :query ""}) events)
                                   :x-command-palette))]
    (testing "a pick is refused to the palette and dispatched, so the state alone closes it"
      ((:on-x-command-palette-select-request (second el)) (fake-event {:item {:id "save"}} prevented))
      (is @prevented)
      (is (= [[:palette-pick "save"]] @events)))
    (testing "a close is refused to the palette and dispatched"
      (reset! events []) (reset! prevented false)
      ((:on-x-command-palette-close-request (second el)) (fake-event {} prevented))
      (is @prevented)
      (is (= [[:palette-close]] @events)))))

(defn- click
  "Calls the `:on-click` of the element `el`, a hiccup vector."
  [el]
  ((:on-click (second el)) nil))

(deftest result-entries
  (let [events (atom [])
        entry  {:id 7 :kind :eval :path "src/a.clj" :line 3 :form "(+ 1 2)" :status :ok :out "hi\n" :value "3"}
        h      (page/entry-view entry false #(swap! events conj %))]
    (testing "the header names the file, the line and the form"
      (is (= "src/a.clj" (:path entry)))
      (is (re-find #"a\.clj:3  \(\+ 1 2\)" (text h))))
    (testing "the output comes before the value"
      (is (re-find #"hi\n.*3" (text h))))
    (testing "a click on the header jumps to the code"
      (click (first (filter #(= "entry-head jumps" (:class (second %))) (elements h :div))))
      (is (= [[:jump-to 7]] @events))))
  (testing "a running entry has an interrupt button"
    (let [events (atom [])
          h      (page/entry-view {:id 7 :kind :eval :path "a.clj" :line 1 :form "x" :status :running}
                                  false #(swap! events conj %))]
      (click (first (elements h :x-button)))
      (is (= [[:interrupt 7]] @events))))
  (testing "an entry that failed shows its message"
    (is (re-find #"boom" (text (page/entry-view {:id 1 :kind :eval :path "a.clj" :line 1 :form "x"
                                                 :status :error :message "boom"} false identity)))))
  (testing "docs show the name, the arglists and the doc"
    (is (re-find #"clojure.core/map.*\[f coll\].*Returns"
                 (text (page/entry-view {:id 1 :kind :doc :sym "map" :status :ok
                                          :info {:ns "clojure.core" :name "map" :arglists-str "([f coll])"
                                                 :doc "Returns a lazy sequence."}}
                                         false identity))))))

(deftest folding-long-results
  (let [long-value (apply str (interpose "\n" (range 20)))]
    (is (= [(apply str (interpose "\n" (range page/fold-lines))) true] (page/folded long-value false)))
    (is (= [long-value false] (page/folded long-value true)))
    (is (= ["1" false] (page/folded "1" false)))
    (testing "a folded entry has a button that shows it all"
      (let [events (atom [])
            h      (page/entry-view {:id 4 :kind :eval :path "a.clj" :line 1 :form "x" :status :ok
                                     :value long-value}
                                    false #(swap! events conj %))]
        (click (last (elements h :x-button)))
        (is (= [[:unfold 4]] @events))))
    (testing "an unfolded entry shows it all, with a button that folds it again"
      (let [h (page/entry-view {:id 4 :kind :eval :path "a.clj" :line 1 :form "x" :status :ok
                                :value long-value}
                               true identity)]
        (is (re-find #"19" (text h)))
        (is (= "Show less" (text (last (elements h :x-button)))))))))

(deftest the-results-column
  (testing "it shows when open, and takes no room when closed"
    (let [open-h   (render (steps/add-entry state {:id 1 :kind :out :out "x"}) (atom []))
          closed-h (render (assoc state :results-open? false) (atom []))]
      (is (seq (elements open-h :x-button)))
      (is (= "100" (some #(when (= "inner closed" (:class (second %))) (:position (second %)))
                         (elements closed-h :x-split-pane))))
      (testing "and the editor stays on the page either way"
        (is (= "" (editor-style closed-h)))))))

(deftest the-look-and-feel
  (testing "the x-theme picks its own preset until the look and feel arrives"
    (is (nil? (:preset (second (render state (atom [])))))))
  (let [h (render (assoc state :config {:preset "ocean" :font-mono "Menlo, monospace" :font-size 14}) (atom []))]
    (testing "then it is the user's"
      (is (= "ocean" (:preset (second h)))))
    (testing "the fonts go on the editor and the results column only"
      (is (= "--x-font-family-mono:Menlo, monospace;--x-font-size-base:14px;" (editor-style h)))
      (is (= (editor-style h) (some #(when (= "results-pane" (:class (second %))) (:style (second %)))
                                    (elements h :div))))
      (is (nil? (:style (second h)))))
    (testing "a font the file does not name is not set"
      (is (= "--x-font-size-base:12px;" (page/font-style {:font-size 12}))))))
