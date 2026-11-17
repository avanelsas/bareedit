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
      (doseq [b (elements h :x-button)] ((:on-click (second b)) nil))
      (is (= [[:answer :cancel] [:answer :discard] [:answer :save]] @events))))
  (testing "only the first question shows"
    (let [s (-> state
                (steps/ask {:kind :disk-change :buffer 1 :disk {:text "x" :version "v2"}})
                (steps/ask {:kind :close :buffer 1}))
          h (render s (atom []))]
      (is (= 1 (count (elements h :x-modal))))
      (is (= ["Take disk" "Keep mine"] (map text (elements h :x-button)))))))
