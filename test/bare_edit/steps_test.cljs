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

(deftest every-command-has-a-method
  (doseq [{:keys [id]} steps/commands]
    (is (some? (get-method steps/run-command id)) (str "run-command has no method for " id))))

(defn- command
  "The command with the id `id`."
  [id]
  (first (filter #(= id (:id %)) steps/commands)))

(deftest palettes
  (let [r (run (opened "(ns a)") [:command (command :file-palette)])
        s (:state r)]
    (is (= {:kind :files :query ""} (:palette s)))
    (is (= [] (:fx r)))
    (testing "the query is stored, and switching palettes starts empty"
      (is (= {:kind :files :query "core"} (:palette (:state (run s [:palette-query "core"])))))
      (is (= {:kind :commands :query ""}
             (:palette (:state (run s [:palette-query "core"] [:command (command :command-palette)]))))))
    (testing "picking a file closes the palette, focuses the editor and opens the file"
      (let [r (run s [:palette-pick "src/b.clj"])]
        (is (nil? (get-in r [:state :palette])))
        (is (= [:focus-editor] (first (:fx r))))
        (is (= "src/b.clj" (get-in r [:state :buffers 2 :path])))))
    (testing "picking a command runs it after the focus"
      (let [r (run (assoc-in s [:palette :kind] :commands) [:palette-pick "new-file"])]
        (is (= [[:focus-editor]] (:fx r)))
        (is (= [{:kind :new-file :path "src/"}] (get-in r [:state :questions])))))
    (testing "Escape closes the palette, and does nothing when none is open"
      (is (nil? (:palette (:state (run s [:command (command :close-palette)])))))
      (is (= {:state connected :fx []} (run connected [:command (command :close-palette)]))))
    (testing "the palette closing itself closes it in the state"
      (is (nil? (:palette (:state (run s [:palette-close]))))))
    (testing "a command picked in the command palette can open the file palette, which keeps the keys"
      (let [r (run (assoc s :palette {:kind :commands :query "go"}) [:palette-pick "file-palette"])]
        (is (= {:kind :files :query ""} (get-in r [:state :palette])))
        (is (= [] (:fx r)))))))

(deftest new-files
  (let [s (:state (run (opened "(ns a)") [:command (command :new-file)]))]
    (is (= [{:kind :new-file :path "src/"}] (:questions s)) "the path starts in the active file's folder")
    (testing "a path with a problem keeps the question open"
      (is (= s (:state (run s [:new-file-path "../x.clj"] [:answer :create] [:new-file-path "src/"])))))
    (testing "a new path opens an empty buffer that is not on disk"
      (let [s2 (:state (run s [:new-file-path "src/new/c.clj"] [:answer :create]))
            b  (get-in s2 [:buffers 2])]
        (is (= [] (:questions s2)))
        (is (= ["src/new/c.clj" "" :absent false] [(:path b) (:disk-text b) (:disk-version b) (steps/unsaved? b)]))
        (is (= 2 (:active s2)))
        (testing "and its first save creates it, then asks for the path list"
          (let [r (run s2 [:command (command :save)])
                [_ sent] (last (:fx r))]
            (is (= :absent (:version sent)))
            (is (= #{:paths} (set (map (comp :op second) (:fx (run (:state r) (reply-to r :ok {:version "v1"})))))))))))
    (testing "a path in the path list opens that file"
      (is (= "src/b.clj" (get-in (run s [:new-file-path "src/b.clj"] [:answer :create]) [:state :buffers 2 :path]))))
    (testing "a path is normalized first, so ./src/b.clj opens src/b.clj"
      (is (= "src/b.clj" (get-in (run s [:new-file-path "./src//b.clj"] [:answer :create]) [:state :buffers 2 :path]))))
    (testing "the path of an open file switches to its tab"
      (is (= 1 (:active (:state (run s [:new-file-path "src/a.clj"] [:answer :create]))))))))

(deftest save-all
  (let [s (-> (opened "(ns a)") (typed "(ns a) 1")
              (assoc-in [:buffers 2] {:id 2 :path "src/b.clj" :disk-text "b" :disk-version "v"
                                      :state (editor/new-state "b")})
              (update :tabs conj 2))
        r (run s [:command (command :save-all)])]
    (is (= [["src/a.clj" :save]] (map (juxt :path :op) (map second (:fx r))))
        "only the unsaved buffer is saved")))

(deftest every-effect-is-a-known-kind
  (let [s  (opened "(ns a)")
        rs [(run s [:command (command :file-palette)] [:palette-pick "src/b.clj"])
            (run s [:command (command :save)])
            (run s [:disconnected])
            (run s [:command (command :refresh-file-tree)])]]
    (is (every? steps/effect-kinds (map first (mapcat :fx rs))))))

(deftest a-question-closes-the-palette
  (let [s (:state (run (typed (opened "(ns a)") "(ns a) mine") [:command (command :file-palette)]))]
    (testing "a file changed on disk while the palette is open closes the palette"
      (let [r (run s [:focus])
            r (run (:state r) (reply-to r :ok {:files {"src/a.clj" {:text "(ns a) disk" :version "v2"}}}))]
        (is (= :disk-change (:kind (first (get-in r [:state :questions])))))
        (is (nil? (get-in r [:state :palette])))))
    (testing "a pick while a question is open does nothing"
      (let [blocked (-> s
                        (steps/ask {:kind :close :buffer 1})
                        (assoc :palette {:kind :commands :query ""}))]
        (is (= {:state blocked :fx []} (run blocked [:palette-pick "new-file"])))))))

(deftest every-command-returns-known-effects
  (let [s (typed (opened "(ns a)") "(ns a) 1")]
    (doseq [c steps/commands
            :let [r (run (assoc s :palette {:kind :files :query ""}) [:command c])]]
      (is (every? steps/effect-kinds (map first (:fx r))) (str (:id c) " returns an unknown effect")))))
