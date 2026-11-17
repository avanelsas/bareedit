(ns bare-edit.steps-test
  "Tests the event steps as values in and values out, with no browser, socket or page."
  (:require ["@codemirror/commands" :refer [undo]]
            [bare-edit.editor :as editor]
            [bare-edit.protocol :as p]
            [bare-edit.steps :as steps]
            [clojure.test :refer [deftest is testing]]))

(defn- run
  "The result of running `events` in order, starting from the state `s`.
  The returned effects are those of the last event."
  [s & events]
  (reduce (fn [r e] (steps/step (assoc r :fx []) e)) (steps/result s) events))

(defn- reply-to
  "The `:message` event that answers, with `status` and `extra`, the last request `r` sent."
  [r status extra]
  (let [[_ req] (last (filter #(= :send (first %)) (:fx r)))]
    [:message (p/encode (p/reply (:id req) status extra))]))

(def connected
  "A connected state whose path list holds `src/a.clj` and `src/b.clj`."
  (:state (run (assoc steps/initial-state :paths ["src/a.clj" "src/b.clj"]) [:connected])))

(defn- opened
  "The [[connected]] state with `src/a.clj` open and loaded with `text`."
  [text]
  (let [r (run connected [:open-file "src/a.clj"])]
    (:state (run (:state r) (reply-to r :ok {:files {"src/a.clj" {:text text :version "v1"}}})))))

(defn- typed
  "Returns the state `s` with the text of buffer 1 replaced by `text`, as if typed."
  [s text]
  (assoc-in s [:buffers 1 :state] (editor/new-state text)))

(deftest connecting-refreshes
  (let [r (run (assoc steps/initial-state :buffers {1 {:id 1 :path "src/a.clj" :disk-text ""}})
               [:connected])]
    (is (= [[:send {:op :paths :id 1}] [:send {:op :read :id 2 :paths ["src/a.clj"]}]] (:fx r)))
    (is (= #{1 2} (set (keys (get-in r [:state :pending])))))))

(deftest opening-a-file
  (testing "a new buffer is loading and a :read is sent"
    (let [{s :state fx :fx} (run connected [:open-file "src/a.clj"])]
      (is (= {:id 1 :path "src/a.clj" :loading? true} (get-in s [:buffers 1])))
      (is (= [1 1] [(:active s) (count (:tabs s))]))
      (is (= :read (:op (second (first fx)))))))
  (testing "the reply loads it"
    (let [b (get-in (opened "(ns a)") [:buffers 1])]
      (is (= ["(ns a)" "(ns a)" "v1" false]
             [(steps/buffer-text b) (:disk-text b) (:disk-version b) (:loading? b)]))))
  (testing "a folder in the tree opens nothing"
    (is (= {:state connected :fx []} (run connected [:open-file "src"]))))
  (testing "an open file is switched to, not opened twice"
    (let [s (opened "")]
      (is (= [1] (:tabs (:state (run s [:open-file "src/a.clj"]))))))))

(deftest edits-go-to-the-buffer-they-started-from
  (let [s   (opened "(ns a)")
        old (get-in s [:buffers 1 :state])
        new (editor/new-state "(ns a) x")]
    (is (identical? new (get-in (run s [:editor-state old new]) [:state :buffers 1 :state])))
    (testing "an edit to a state no buffer holds is dropped"
      (is (= s (:state (run s [:editor-state (editor/new-state "") new])))))))

(deftest saving
  (let [s (typed (opened "(ns a)") "(ns a) 1")]
    (testing "a second save while the first waits is refused with a toast"
      (let [{fx :fx} (run s [:command {:id :save}] [:command {:id :save}])]
        (is (= [[:toast :info "Still saving a.clj."]] fx))))
    (testing "the reply records the text that was sent as the disk text"
      (let [r (run s [:command {:id :save}])
            s (:state (run (:state (update r :state typed "(ns a) 12")) (reply-to r :ok {:version "v2"})))]
        (is (= ["(ns a) 1" "v2"] [(get-in s [:buffers 1 :disk-text]) (get-in s [:buffers 1 :disk-version])]))
        (is (steps/unsaved? (get-in s [:buffers 1])))))))

(deftest save-and-close
  (let [s (typed (opened "(ns a)") "(ns a) 1")
        r (run s [:command {:id :close-tab}])]
    (is (= [{:kind :close :buffer 1}] (get-in r [:state :questions])))
    (testing "commands wait while the dialog is open"
      (is (= {:state (:state r) :fx []} (run (:state r) [:command {:id :next-tab}]))))
    (let [r (run (:state r) [:answer :save])]
      (testing "the follow-up stays with the pending request and is not sent"
        (let [[_ sent] (first (:fx r))]
          (is (= [:close-tab 1] (:then (get-in r [:state :pending (:id sent)]))))
          (is (not (contains? sent :then)))))
      (testing "a save that succeeds closes the tab"
        (is (= [] (:tabs (:state (run (:state r) (reply-to r :ok {:version "v2"})))))))
      (testing "text typed after the save went out keeps the tab open and asks again"
        (let [s2 (:state (run (typed (:state r) "(ns a) 12") (reply-to r :ok {:version "v2"})))]
          (is (= [1] (:tabs s2)))
          (is (= [{:kind :close :buffer 1}] (:questions s2)))))
      (testing "a save that conflicts keeps it open and reads the file; the follow-up is gone"
        (let [r2 (run (:state r) (reply-to r :conflict {:message "changed"}))]
          (is (= [1] (:tabs (:state r2))))
          (is (not-any? :then (vals (get-in r2 [:state :pending]))))
          (testing "so a later save that succeeds leaves the tab open"
            (let [r3 (run (:state r2) [:command {:id :save}])]
              (is (= [1] (:tabs (:state (run (:state r3) (reply-to r3 :ok {:version "v3"})))))))))))))

(deftest disconnected
  (let [s (assoc-in connected [:pending 9] {:op :paths :id 9})
        r (run s [:disconnected] [:command {:id :save}] [:focus])]
    (is (= [:closed {}] [(get-in r [:state :conn]) (get-in r [:state :pending])]))
    (testing "nothing is sent, and a request says so"
      (is (= [] (:fx (run (:state r) [:focus]))))
      (is (= [[:toast :error "Not connected to bare-edit. Restart it to keep editing."]]
             (:fx (steps/send (steps/result (:state r)) :paths {})))))))

(deftest reloading-keeps-cursor-and-history
  (let [s  (opened "(ns a)\n(def x 1)\n(def y 2)\n")
        ^js st (get-in s [:buffers 1 :state])
        s  (-> s
               (assoc-in [:buffers 1 :state] (.-state (.update st #js {:selection #js {:anchor 20}}))) ; in (def y 2)
               (assoc-in [:pending 5] {:op :read :id 5}))
        s  (:state (run s [:message (p/encode (p/reply 5 :ok {:files {"src/a.clj" {:text "(ns a)\n(def x 100)\n(def y 2)\n"
                                                                                   :version "v2"}}}))]))
        b  (get-in s [:buffers 1])]
    (is (= "(ns a)\n(def x 100)\n(def y 2)\n" (steps/buffer-text b)))
    (is (not (steps/unsaved? b)))
    (testing "the cursor moves with the text after the change"
      (is (= 22 (.. ^js (:state b) -selection -main -head))))
    (testing "undo goes back to the text before the reload, which is then unsaved"
      (let [undone (atom nil)]
        (undo #js {:state (:state b) :dispatch #(reset! undone (.-state %))})
        (is (= "(ns a)\n(def x 1)\n(def y 2)\n" (editor/text @undone)))
        (is (steps/unsaved? (assoc b :state @undone)))))))

(deftest a-failed-read-closes-the-tabs-waiting-for-it
  (let [r (run connected [:open-file "src/a.clj"])
        r (run (:state r) (reply-to r :error {:message "Operation :read failed: denied"}))]
    (is (= [] (get-in r [:state :tabs])))
    (is (= {} (get-in r [:state :buffers])))
    (is (= [[:toast :error "Operation :read failed: denied"]] (:fx r)))))

(defn- read-reply
  "The `:message` event answering the last read `r` sent, with `text` at version `version`."
  [r text version]
  (reply-to r :ok {:files {"src/a.clj" {:text text :version version}}}))

(deftest questions
  (let [s (assoc (typed (opened "(ns a)") "(ns a) mine") :pending {})
        r (run s [:focus])]
    (testing "a file changed on disk while it has unsaved changes is asked about"
      (let [r (run (:state r) (read-reply r "(ns a) disk" "v2"))]
        (is (= [{:kind :disk-change :buffer 1 :disk {:text "(ns a) disk" :version "v2"}}]
               (get-in r [:state :questions])))
        (testing "a second change replaces the question, with the latest text"
          (let [r2 (run (:state r) [:focus])
                r2 (run (:state r2) (read-reply r2 "(ns a) disk 2" "v3"))]
            (is (= [{:kind :disk-change :buffer 1 :disk {:text "(ns a) disk 2" :version "v3"}}]
                   (get-in r2 [:state :questions])))))
        (testing "keep mine takes the disk's version and keeps the text"
          (let [b (get-in (run (:state r) [:answer :keep]) [:state :buffers 1])]
            (is (= ["(ns a) mine" "(ns a) disk" "v2"]
                   [(steps/buffer-text b) (:disk-text b) (:disk-version b)]))))
        (testing "take disk takes the text as well"
          (is (= "(ns a) disk"
                 (steps/buffer-text (get-in (run (:state r) [:answer :take]) [:state :buffers 1])))))
        (testing "closing the buffer drops its questions"
          (is (= [] (:questions (steps/close-buffer (:state r) 1)))))))
    (testing "answering with no question open changes nothing"
      (is (= {:state connected :fx []} (run connected [:answer :keep]))))))
