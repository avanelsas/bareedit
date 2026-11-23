(ns bare-edit.steps-test
  "Tests the event steps as values in and values out, with no browser, socket or page."
  (:require ["@codemirror/commands" :refer [undo]]
            ["@codemirror/language" :refer [syntaxTree]]
            [bare-edit.editor :as editor]
            [bare-edit.protocol :as p]
            [bare-edit.settings :as settings]
            [bare-edit.steps :as steps]
            [clojure.test :refer [deftest is testing]]))

(defn- run
  "The result of running `events` in order, starting from the state `s`.
  The returned effects are those of the last event."
  [s & events]
  (reduce (fn [r e] (steps/advance (assoc r :fx []) e)) (steps/result s) events))

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
    (is (= [[:send {:op :paths :id 1}] [:send {:op :read :id 2 :paths ["src/a.clj"] :known {}}]
            [:send {:op :config :id 3}] [:send {:op :project :id 4}]]
           (:fx r)))
    (is (= #{1 2 3 4} (set (keys (get-in r [:state :pending])))))))

(deftest the-look-and-feel
  (let [r (run steps/initial-state [:connected])
        s (:state r)]
    (testing "it is asked for on connecting, until it arrives"
      (is (some #{[:send {:op :config :id 2}]} (:fx r))))
    (let [r2 (run s (reply-to (update r :fx #(filterv (fn [[_ req]] (= :config (:op req))) %))
                              :ok {:config {:preset "ocean" :font-size 14} :problems ["one" "two"]}))]
      (testing "the reply is stored, and each problem is a warning toast"
        (is (= {:preset "ocean" :font-size 14} (get-in r2 [:state :config])))
        (is (= [[:toast :warning "one"] [:toast :warning "two"]] (:fx r2))))
      (testing "connecting again does not ask again"
        (let [r3 (run (:state r2) [:disconnected] [:connected])]
          (is (not-any? #(= :config (:op (second %))) (:fx r3))))))))

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
        (is (= [[:toast :info "Still saving or renaming a.clj."]] fx))))
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
      (is (= [[:toast :error "Not connected to bareedit. Restart it to keep editing."]]
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
  (doseq [c steps/commands]
    (is (some? (get-method steps/run-command (steps/command-dispatch nil c)))
        (str "run-command has no method for " (:id c)))))

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
      (is (nil? (:palette (:state (run s [:command (command :close)])))))
      (is (= {:state connected :fx []} (run connected [:command (command :close)]))))
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
      (is (= s (:state (run s [:question-path "../x.clj"] [:answer :create] [:question-path "src/"])))))
    (testing "a new path opens an empty buffer that is not on disk"
      (let [s2 (:state (run s [:question-path "src/new/c.clj"] [:answer :create]))
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
      (is (= "src/b.clj" (get-in (run s [:question-path "src/b.clj"] [:answer :create]) [:state :buffers 2 :path]))))
    (testing "a path is normalized first, so ./src/b.clj opens src/b.clj"
      (is (= "src/b.clj" (get-in (run s [:question-path "./src//b.clj"] [:answer :create]) [:state :buffers 2 :path]))))
    (testing "the path of an open file switches to its tab"
      (is (= 1 (:active (:state (run s [:question-path "src/a.clj"] [:answer :create]))))))))

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

(deftest editor-commands-run-on-the-editor
  (let [slurp (command :slurp-forward)]
    (is (= [[:edit :slurp-forward]] (:fx (run (opened "(ns a)") [:command slurp]))))
    (testing "with no loaded buffer, nothing runs"
      (is (= [] (:fx (run connected [:command slurp])))))
    (testing "picked in the command palette, the editor gets the focus first"
      (is (= [[:focus-editor] [:edit :slurp-forward]]
             (:fx (run (assoc (opened "(ns a)") :palette {:kind :commands :query ""})
                       [:palette-pick "slurp-forward"])))))))

(defn- at-cursor
  "Returns the state `s` with buffer 1 holding `text`, with the cursor where `|` is."
  [s text]
  (let [i  (.indexOf text "|")
        st (editor/new-state (str (subs text 0 i) (subs text (inc i))))]
    (assoc-in s [:buffers 1 :state] (.-state (.update st #js {:selection #js {:anchor i}})))))

(deftest evaluating
  (let [s (at-cursor (opened "") "(ns a.b)\n(+ 1 (* 2 3)|)")
        r (run s [:command (command :eval-form)])
        [[_ req]] (:fx r)]
    (testing "the form at the cursor is sent in the buffer's namespace, with its line"
      (is (= {:op :eval :path "src/a.clj" :ns "a.b" :line 2 :code "(* 2 3)"} (dissoc req :id))))
    (testing "an entry for it is running at the top of the results"
      (is (= {:id (:id req) :kind :eval :path "src/a.clj" :line 2 :form "(* 2 3)" :status :running}
             (first (get-in r [:state :results])))))
    (testing "its reply fills the entry in, whatever the status"
      (let [r2 (run (:state r) (reply-to r :error {:message "boom" :err "trace"}))]
        (is (= {:status :error :message "boom" :err "trace"}
               (select-keys (first (get-in r2 [:state :results])) [:status :message :err])))
        (is (= [] (:fx r2)) "no toast: the entry shows it")))
    (testing "the top-level form"
      (is (= "(+ 1 (* 2 3))" (:code (second (first (:fx (run s [:command (command :eval-top-form)]))))))))
    (testing "the file sends the buffer's text"
      (is (= {:op :load-file :path "src/a.clj" :text "(ns a.b)\n(+ 1 (* 2 3))"}
             (dissoc (second (first (:fx (run s [:command (command :eval-file)])))) :id))))
    (testing "with no connection, no entry is added"
      (let [r (run (assoc s :conn :closed) [:command (command :eval-form)])]
        (is (= [] (get-in r [:state :results])))
        (is (= :toast (ffirst (:fx r))))))))

(deftest interrupting
  (let [s (-> (opened "")
              (steps/add-entry {:id 7 :kind :eval :status :running})
              (steps/add-entry {:id 8 :kind :eval :status :ok})
              (steps/add-entry {:id 9 :kind :doc :status :running}))]
    (testing "the command interrupts the newest evaluation still running"
      (is (= {:op :interrupt :eval 7} (dissoc (second (first (:fx (run s [:command (command :interrupt)])))) :id))))
    (testing "an entry's button interrupts that evaluation"
      (is (= 8 (:eval (second (first (:fx (run s [:interrupt 8]))))))))
    (testing "an interrupt's message shows as a toast"
      (let [r (run s [:interrupt 7])]
        (is (= [[:toast :info "careful"]] (:fx (run (:state r) (reply-to r :ok {:message "careful"})))))))
    (testing "the newest evaluation still running is found"
      (is (= 7 (steps/newest-running s)))
      (is (nil? (steps/newest-running (opened "")))))
    (testing "with nothing running, nothing is sent"
      (is (= [] (:fx (run (opened "") [:command (command :interrupt)])))))))

(deftest showing-docs
  (let [r (run (at-cursor (opened "") "(ns a.b)\n(ma|p inc xs)") [:command (command :show-docs)])
        [[_ req]] (:fx r)]
    (is (= {:op :lookup :path "src/a.clj" :sym "map" :ns "a.b"} (dissoc req :id)))
    (is (= :doc (:kind (first (get-in r [:state :results])))))
    (let [r2 (run (:state r) (reply-to r :ok {:info {:name "map"}}))]
      (is (= {:name "map"} (:info (first (get-in r2 [:state :results]))))))))

(deftest going-to-definitions
  (let [s (at-cursor (opened "") "(ns a.b)\n(fo|o 1)")
        r (run s [:command (command :go-to-definition)])
        [[_ req]] (:fx r)
        answer (fn [extra] (run (:state r) (reply-to r :ok extra)))]
    (testing "it asks nREPL about the symbol, with no results entry"
      (is (= {:op :lookup :path "src/a.clj" :sym "foo" :ns "a.b"} (dissoc req :id)))
      (is (empty? (get-in r [:state :results]))))
    (testing "a definition in a project file opens it at its line"
      (let [r2 (answer {:info {:file "b.clj" :line 12}})]
        (is (= "src/b.clj" (get-in r2 [:state :buffers 2 :path])))
        (is (= 12 (get-in r2 [:state :buffers 2 :goto])))
        (is (empty? (get-in r2 [:state :results])))))
    (testing "a file: URL, as nREPL gives for code loaded by require, names a project file"
      (is (= "src/b.clj" (get-in (answer {:info {:file "file:/Users/me/proj/src/b.clj" :line 2}})
                                 [:state :buffers 2 :path]))))
    (testing "with no line, the file opens at the top"
      (is (= 1 (get-in (answer {:info {:file "src/b.clj"}}) [:state :buffers 2 :goto]))))
    (testing "in the open file, the cursor moves"
      (is (= [[:goto 3]] (:fx (answer {:info {:file "src/a.clj" :line "3"}})))))
    (testing "a symbol not loaded, or defined outside the project, is a toast"
      (doseq [[info text] [[{} "foo is not loaded. Evaluate its namespace first."]
                           [{:file "clojure/core.clj" :line 4}
                            "foo is defined in clojure/core.clj, line 4, outside the project."]
                           [{:file "NO_SOURCE_PATH" :line 1}
                            "foo is defined in NO_SOURCE_PATH, line 1, outside the project."]
                           [{:file "clojure/core.clj"}
                            "foo is defined in clojure/core.clj, outside the project."]
                           [{:file "jar:file:/m2/clojure.jar!/clojure/core.clj" :line 4}
                            "foo is defined in clojure/core.clj, line 4, outside the project."]]]
        (is (= [[:toast :info text]] (:fx (answer {:info info}))))))
    (testing "a file that several project paths end with names them all"
      (let [s2 (assoc s :paths ["src/a.clj" "src/x/c.clj" "test/x/c.clj"])
            r2 (run s2 [:command (command :go-to-definition)])]
        (is (= [[:toast :info "x/c.clj could be src/x/c.clj or test/x/c.clj."]]
               (:fx (run (:state r2) (reply-to r2 :ok {:info {:file "x/c.clj" :line 1}})))))))
    (testing "a failed lookup shows its message"
      (is (= [[:toast :error "No REPL."]]
             (:fx (run (:state r) [:message (p/encode (p/failure (:id req) :unavailable "No REPL."))])))))
    (testing "a file that is not Clojure is refused"
      (let [s2 (assoc-in s [:buffers 1 :path] "src/a.cljs")]
        (is (= [steps/definition-refusal] (:fx (run s2 [:command (command :go-to-definition)]))))))
    (testing "with no symbol at the cursor, nothing happens"
      (is (= [] (:fx (run (at-cursor (opened "") "|") [:command (command :go-to-definition)])))))))

(deftest opening-locations
  (let [r (run connected [:open-location "src/b.clj" 7])]
    (is (= "src/b.clj" (get-in r [:state :buffers 1 :path])))
    (is (= 7 (get-in r [:state :buffers 1 :goto])))))

(deftest escape-with-several-selections
  (let [s  (at-cursor (opened "") "(a x| x)")
        st (get-in s [:buffers 1 :state])
        two (.-state (editor/edit (.-state (editor/edit st :select-next-occurrence)) :select-next-occurrence))]
    (testing "Escape keeps one selection when there are several"
      (is (= [[:edit :single-selection]]
             (:fx (run (assoc-in s [:buffers 1 :state] two) [:command (command :close)])))))
    (testing "with one selection it does nothing"
      (is (= [] (:fx (run s [:command (command :close)])))))))

(deftest the-results-column
  (let [s (-> (opened "") (steps/add-entry {:id 7 :kind :eval :path "src/a.clj" :line 3 :status :running}))]
    (testing "a disconnect leaves no entry running"
      (is (= :unavailable (:status (first (get-in (run s [:disconnected]) [:state :results]))))))
    (testing "output that belongs to no evaluation gets an entry"
      (let [r (run s [:message (p/encode (p/event :out {:text "hi\n"}))])]
        (is (= {:kind :out :out "hi\n"} (dissoc (first (get-in r [:state :results])) :id)))))
    (testing "clearing empties it, and toggling hides it"
      (is (= [] (get-in (run s [:command (command :clear-results)]) [:state :results])))
      (is (false? (get-in (run s [:command (command :toggle-results)]) [:state :results-open?]))))
    (testing "a new entry shows it again"
      (let [closed (assoc s :results-open? false)]
        (is (true? (get-in (run closed [:command (command :eval-file)]) [:state :results-open?])))
        (is (true? (get-in (run closed [:message (p/encode (p/event :out {:text "x"}))]) [:state :results-open?])))))
    (testing "an entry can be shown in full, and folded again"
      (let [r (run s [:unfold 7])]
        (is (= #{7} (get-in r [:state :unfolded])))
        (is (= #{} (get-in (run (:state r) [:unfold 7]) [:state :unfolded])))))
    (testing "the entries record what happened, and nothing about how they show"
      (is (= (:results s) (get-in (run s [:unfold 7]) [:state :results]))))
    (testing "it keeps the newest entries only"
      (let [many (reduce #(steps/add-entry %1 {:id %2 :kind :out}) s (range 200))]
        (is (= steps/max-results (count (:results many))))
        (is (= 199 (:id (first (:results many)))))))
    (testing "jumping goes to the line when the file is open, or opens it"
      (is (= [[:goto 3]] (:fx (run s [:jump-to 7]))))
      (let [s2 (steps/add-entry s {:id 8 :kind :eval :path "src/b.clj" :line 1})]
        (is (= :read (:op (second (first (:fx (run s2 [:jump-to 8])))))))))))

(deftest completions-from-the-repl
  (let [s (at-cursor (opened "") "(ns a.b)\n(ma|)")
        r (run s [:complete {:prefix "ma" :token 3}])
        [[_ req]] (:fx r)]
    (testing "a Clojure file asks the REPL, in its namespace"
      (is (= {:op :completions :prefix "ma" :ns "a.b"} (dissoc req :id))))
    (testing "the candidates answer the editor's request"
      (let [cands [{:candidate "map" :type "function" :ns "clojure.core"}]]
        (is (= [[:completed 3 cands]] (:fx (run (:state r) (reply-to r :ok {:candidates cands})))))))
    (testing "no REPL answers nil, so the editor offers the file's words, with no toast"
      (is (= [[:completed 3 nil]] (:fx (run (:state r) (reply-to r :unavailable {:message "No REPL"}))))))
    (testing "a disconnect answers the requests still waiting"
      (is (some #{[:completed 3 nil]} (:fx (run (:state r) [:disconnected])))))
    (testing "any other file answers nil at once"
      (let [s2 (assoc-in s [:buffers 1 :path] "src/a.cljs")]
        (is (= [[:completed 4 nil]] (:fx (run s2 [:complete {:prefix "ma" :token 4}]))))))
    (testing "so does a page with no connection"
      (is (= [[:completed 5 nil]] (:fx (run (assoc s :conn :closed) [:complete {:prefix "ma" :token 5}])))))))

(deftest closing-a-tab-from-its-button
  (testing "a saved tab closes"
    (is (empty? (:tabs (:state (run (opened "(ns a)") [:close-tab 1]))))))
  (testing "a tab with unsaved changes asks first"
    (is (= [{:kind :close :buffer 1}]
           (:questions (:state (run (typed (opened "(ns a)") "(ns b)") [:close-tab 1])))))))

(deftest the-project-name
  (let [r  (run steps/initial-state [:connected])
        r2 (run (:state r) (reply-to r :ok {:name "demo"}))]
    (is (= "demo" (get-in r2 [:state :project])))
    (is (false? (get-in r2 [:state :read-only?])))
    (testing "a read-only project says so"
      (is (true? (get-in (run (:state r) (reply-to r :ok {:name "demo" :read-only true}))
                         [:state :read-only?]))))
    (testing "connecting again does not ask again"
      (is (not-any? #(= :project (:op (second %))) (:fx (run (:state r2) [:disconnected] [:connected])))))))

(deftest what-the-repls-said
  (let [s  (opened "(ns a)\n(+ 1 2)")
        r  (run s [:command (steps/command-by-name "eval-top-form")])
        id (:id (second (first (:fx r))))]
    (testing "an evaluation marks its REPL busy"
      (is (= {:status :running :message nil} (get-in r [:state :repl :clj]))))
    (testing "its reply says what the REPL did"
      (let [r2 (run (:state r) [:message (p/encode (p/reply id :unavailable {:message "No REPL."}))])]
        (is (= {:status :unavailable :message "No REPL."} (get-in r2 [:state :repl :clj])))
        (testing "and clearing the results keeps it"
          (is (= :unavailable (get-in (run (:state r2) [:command (steps/command-by-name "clear-results")])
                                      [:state :repl :clj :status]))))
        (testing "and a disconnect forgets it"
          (is (= {} (get-in (run (:state r2) [:disconnected]) [:state :repl]))))))))

(defn- renaming
  "The result of asking to rename the file of `s`'s active tab to `path`."
  [s path]
  (run s [:command (steps/command-by-name "rename-file")] [:question-path path] [:answer :rename]))

(deftest renaming-a-file
  (let [s (typed (opened "(ns a)") "(ns a) 1")]
    (testing "the question starts from the file's own path"
      (is (= [{:kind :rename :buffer 1 :path "src/a.clj"}]
             (:questions (:state (run s [:command (steps/command-by-name "rename-file")]))))))
    (testing "its own path renames nothing"
      (let [r (renaming s "src/a.clj")]
        (is (empty? (:questions (:state r))))
        (is (empty? (:fx r)))))
    (testing "a path that names another file keeps the question open"
      (let [s2 (:state (renaming s "src/b.clj"))]
        (is (= "A file with that path exists." (steps/question-problem s2 (first (:questions s2)))))))
    (let [r (renaming s "src/util/c.clj")]
      (testing "a saved file is renamed on disk, with the version it was loaded at"
        (is (= [:send {:op :rename :id 5 :from "src/a.clj" :to "src/util/c.clj" :version "v1"}]
               (first (:fx r)))))
      (testing "once renamed, its tab holds the new path and keeps its unsaved text"
        (let [r2 (run (:state r) (reply-to r :ok {:version "v1"}))]
          (is (= "src/util/c.clj" (get-in r2 [:state :buffers 1 :path])))
          (is (steps/unsaved? (get-in r2 [:state :buffers 1])))
          (is (some #{[:toast :info "Renamed a.clj to c.clj."]} (:fx r2)))
          (is (some #(= :paths (:op (second %))) (:fx r2)))))
      (testing "a file changed on disk keeps its path, and is read again"
        (let [r2 (run (:state r) (reply-to r :conflict {:message "src/a.clj changed on disk."}))]
          (is (= "src/a.clj" (get-in r2 [:state :buffers 1 :path])))
          (is (some #(= [:read ["src/a.clj"]] [(:op (second %)) (:paths (second %))]) (:fx r2))))))
    (testing "a new file never saved takes the new path at once"
      (let [s2 (:state (run s [:command (steps/command-by-name "new-file")] [:question-path "src/n.clj"] [:answer :create]))
            r  (renaming s2 "src/m.clj")]
        (is (= "src/m.clj" (get-in r [:state :buffers 2 :path])))
        (is (not-any? #(= :send (first %)) (:fx r)))
        (testing "and a file open in a tab cannot be renamed onto"
          (is (= "That file is open in a tab."
                 (steps/question-problem (:state r) {:kind :rename :buffer 1 :path "src/m.clj"}))))))))

(deftest a-file-being-saved-or-renamed-waits
  (let [s (typed (opened "(ns a)") "(ns a) 1")]
    (testing "a save during a rename is refused"
      (let [r (renaming s "src/c.clj")]
        (is (= [[:toast :info "Still saving or renaming a.clj."]]
               (:fx (run (:state r) [:command (steps/command-by-name "save")]))))))
    (testing "a rename during a save is refused"
      (let [r (run s [:command (steps/command-by-name "save")])]
        (is (= [[:toast :info "Still saving or renaming a.clj."]]
               (:fx (renaming (:state r) "src/c.clj"))))))))

(deftest every-group-is-known
  (doseq [{:keys [id group]} steps/commands :when group]
    (is (contains? steps/group-order group) (str id " has a known group"))))

(deftest unreadable-folders-are-told-once
  (let [r     (run connected [:command {:id :refresh-file-tree}])
        reply (fn [r folders] (run (:state r) (reply-to r :ok {:paths ["src/a.clj"] :unreadable folders})))
        once  (reply r ["Library" "locked"])]
    (is (= ["Library" "locked"] (get-in once [:state :unreadable])))
    (is (= [[:toast :warning "Could not read 2 folders: Library, locked. They are left out of the file tree."]]
           (filter #(= :toast (first %)) (:fx once))))
    (testing "the same folders again show no toast"
      (let [again (reply (run (:state once) [:command {:id :refresh-file-tree}]) ["Library" "locked"])]
        (is (not-any? #(= :toast (first %)) (:fx again)))))
    (testing "no unreadable folders show no toast"
      (is (not-any? #(= :toast (first %)) (:fx (reply r [])))))))

(deftest unreadable-text
  (is (= "Could not read the folder Desktop. It is left out of the file tree."
         (steps/unreadable-text ["Desktop"])))
  (is (= "Could not read 5 folders: a, b, c and 2 more. They are left out of the file tree."
         (steps/unreadable-text ["a" "b" "c" "d" "e"])))
  (is (= "Could not read the project folder." (steps/unreadable-text ["."]))))

(defn- settings-open
  "The result of opening the settings tab from the [[connected]] state, read with `disk`."
  [disk]
  (let [r (run connected [:command {:id :open-settings}])]
    (run (:state r) (reply-to r :ok disk))))

(defn- sent
  "The requests the result `r` sends, without their ids."
  [r]
  (for [[kind req] (:fx r) :when (= :send kind)] (dissoc req :id)))

(deftest saving-and-loading
  (let [s (typed (opened "(ns a)") "(ns a)\n(def x 2)")
        r (run s [:command (command :save-and-load)])]
    (testing "it saves the file first"
      (is (= [{:op :save :path "src/a.clj" :text "(ns a)\n(def x 2)" :version "v1"}] (sent r))))
    (testing "once saved, it loads the text that was saved, with a results entry"
      (let [r2 (run (typed (:state r) "(ns a)\n(def x 3)") (reply-to r :ok {:version "v2"}))]
        (is (= [{:op :load-file :path "src/a.clj" :text "(ns a)\n(def x 2)"}] (sent r2)))
        (is (= "(load-file \"src/a.clj\")" (:form (first (get-in r2 [:state :results])))))))
    (testing "a save that fails loads nothing"
      (doseq [status [:conflict :error]]
        (is (not-any? #(= :load-file (:op %))
                      (sent (run (:state r) (reply-to r status {:message "no"}))))))))
  (testing "a .cljs file and a file that is not Clojure are refused with a toast"
    (doseq [[path kind] [["src/a.cljs" :cljs] ["README.md" nil]]]
      (let [s (assoc-in (opened "x") [:buffers 1 :path] path)]
        (is (= [(steps/load-refusals kind)] (:fx (run s [:command (command :save-and-load)]))))))))

(deftest the-settings-tab
  (testing "opening asks for the settings file, not a project file"
    (is (= [{:op :read-settings}] (sent (run connected [:command {:id :open-settings}])))))
  (testing "with no file, the tab holds every setting, as a file still to create"
    (let [b (get-in (settings-open {:text "" :version :absent}) [:state :buffers 1])]
      (is (= [:settings (settings/default-text) :absent] [(:path b) (:disk-text b) (:disk-version b)]))))
  (let [r (settings-open {:text "{}" :version "v0"})
        b (get-in r [:state :buffers 1])]
    (testing "with a file, the tab holds its text"
      (is (= [:settings "{}" "v0"] [(:path b) (:disk-text b) (:disk-version b)])))
    (testing "saving sends the text with the version it was read with"
      (is (= [{:op :save-settings :text "{}" :version "v0"}]
             (sent (run (:state r) [:command {:id :save}])))))
    (testing "a saved file asks for the look again, and not for the path list"
      (let [saving (run (:state r) [:command {:id :save}])
            saved  (run (:state saving) (reply-to saving :ok {:version "v1"}))]
        (is (= "v1" (get-in saved [:state :buffers 1 :disk-version])))
        (is (= [{:op :config}] (sent saved)))))
    (testing "the focus reads it again with the path list"
      (is (= [{:op :paths} {:op :read-settings}] (sent (run (:state r) [:focus])))))
    (testing "rename and evaluation refuse it with a toast"
      (doseq [id [:rename-file :eval-form :eval-top-form :eval-file :show-docs]]
        (let [done (run (:state r) [:command {:id id}])]
          (is (= [] (sent done)) id)
          (is (= :toast (ffirst (:fx done))) id))))))

(deftest a-refresh-sends-the-versions-it-holds
  (let [s (opened "(ns a)")
        r (run s [:focus])]
    (is (= {:op :read :paths ["src/a.clj"] :known {"src/a.clj" "v1"}}
           (dissoc (second (second (:fx r))) :id)))
    (testing "an unchanged file comes back without text, and the buffer stays as it was"
      (let [after (run (:state r) (reply-to r :ok {:files {"src/a.clj" {:version "v1"}}}))]
        (is (= (get-in s [:buffers 1]) (get-in after [:state :buffers 1])))))))

(deftest a-reply-without-text-never-loads-a-tab
  (let [focused  (run (opened "(ns a)") [:focus])
        ;; the tab closes and the file opens again before the focus read answers
        reopened (run (:state focused) [:close-tab 1] [:open-file "src/a.clj"])
        id       (first (:tabs (:state reopened)))
        early    (run (:state reopened) (reply-to focused :ok {:files {"src/a.clj" {:version "v1"}}}))]
    (is (get-in early [:state :buffers id :loading?]) "it still waits for its own read")
    (let [loaded (run (:state early) (reply-to reopened :ok {:files {"src/a.clj" {:text "(ns a)" :version "v1"}}}))]
      (is (= "(ns a)" (get-in loaded [:state :buffers id :disk-text]))))))

(deftest the-page-config-is-the-look-until-the-server-answers
  (is (= {:preset "forest"} (:config (:state (run steps/initial-state [:page-config {:preset "forest"}]))))))

(deftest structural-editing-works-in-clojure-files-only
  (let [opened-as (fn [path]
                    (let [r (run (assoc connected :paths [path]) [:open-file path])]
                      (:state (run (:state r) (reply-to r :ok {:files {path {:text "(a b)" :version "v1"}}})))))]
    (is (= [[:edit :slurp-forward]] (:fx (run (opened-as "src/a.clj") [:command {:id :slurp-forward}]))))
    (is (= [[:toast :info "This command works in Clojure files only."]]
           (:fx (run (opened-as "README.md") [:command {:id :slurp-forward}]))))
    (testing "commands for any text still run"
      (is (= [[:edit :toggle-comment]] (:fx (run (opened-as "README.md") [:command {:id :toggle-comment}])))))))

(deftest a-rename-to-another-language-changes-the-editor
  (let [created (:state (run connected [:command (steps/command-by-name "new-file")]
                             [:question-path "notes.md"] [:answer :create]))
        top     (fn [s] (.. (syntaxTree (get-in s [:buffers (:active s) :state])) -topNode -name))]
    (is (= "Document" (top created)) "a new .md file is Markdown")
    (is (= "Program" (top (:state (run created [:command (steps/command-by-name "rename-file")]
                                    [:question-path "notes.clj"] [:answer :rename]))))
        "renamed to .clj, it is Clojure")))

(deftest finding-in-the-file
  (let [s (opened "(ns a)")
        r (run s [:command (command :find)])]
    (is (= :file (get-in r [:state :find :scope])))
    (is (= [[:focus-find]] (:fx r)))
    (testing "next and previous run on the editor"
      (let [q (steps/find-query (:state r))]
        (is (= [[:find-in-editor :next-match q ""]] (:fx (run (:state r) [:command (command :find-next)]))))
        (is (= [[:find-in-editor :previous-match q ""]] (:fx (run (:state r) [:command (command :find-previous)]))))))
    (testing "only the query's keys can be set"
      (is (= (:state r) (:state (run (:state r) [:find-set :results {:found {:matches []}}])))))
    (testing "the editor marks the query, and a replacement leaves the marks alone"
      (let [s2 (:state (run (:state r) [:find-set :text "ns"]))
            q  {:text "ns" :case false :regexp false :word false}]
        (is (= q (steps/find-query s2)))
        (is (= q (steps/find-query (:state (run s2 [:find-replacement "x"])))))
        (is (= [[:find-in-editor :replace-all q "x"]]
               (:fx (run s2 [:find-replacement "x"] [:find-edit :replace-all]))))))
    (testing "Enter and the down arrow go to the next match, Shift-Enter and the up arrow to the previous"
      (let [q (steps/find-query (:state r))]
        (is (= [[:find-in-editor :next-match q ""]] (:fx (run (:state r) [:find-key "Enter" false]))))
        (is (= [[:find-in-editor :previous-match q ""]] (:fx (run (:state r) [:find-key "Enter" true]))))
        (is (= [[:find-in-editor :next-match q ""]] (:fx (run (:state r) [:find-key "ArrowDown" false]))))
        (is (= [[:find-in-editor :previous-match q ""]] (:fx (run (:state r) [:find-key "ArrowUp" false]))))))
    (testing "Escape closes the bar and gives the editor the focus"
      (let [r2 (run (:state r) [:command (command :close)])]
        (is (nil? (get-in r2 [:state :find])))
        (is (= [[:focus-editor]] (:fx r2)))))))

(deftest finding-in-the-project
  (let [r  (run (opened "(ns a)") [:command (command :find-in-project)] [:find-set :text "defn"])
        [_ req] (first (filter #(= :send (first %)) (:fx r)))]
    (is (= {:op :search :query {:text "defn" :case false :regexp false :word false}} (dissoc req :id)))
    (let [matches [{:path "src/a.clj" :line 3 :text "(defn f [])"} {:path "src/b.clj" :line 7 :text "(defn g [])"}]
          s       (:state (run (:state r) (reply-to r :ok {:matches matches :more false})))]
      (is (= matches (get-in s [:find :results :found :matches])))
      (testing "an open file goes to the line"
        (let [r2 (run s [:command (command :find-next)])]
          (is (= {:path "src/a.clj" :line 3} (get-in r2 [:state :find :results :selected])))
          (is (= [[:reveal-hit] [:goto 3]] (:fx r2)))))
      (testing "a file that is not open goes to the line once it has loaded"
        (let [r2 (run s [:find-open 1])
              r3 (run (:state r2) (reply-to r2 :ok {:files {"src/b.clj" {:text "(ns b)" :version "v1"}}}))]
          (is (= [[:goto 7]] (:fx r3)))
          (is (nil? (get-in r3 [:state :buffers 2 :goto])))))
      (testing "previous wraps around to the last match"
        (is (= {:path "src/b.clj" :line 7} (get-in (run s [:command (command :find-previous)]) [:state :find :results :selected]))))
      (testing "the arrow keys select a match without opening it, and unfold its file"
        (let [r2 (run (assoc-in s [:find :results :folded] #{"src/b.clj"}) [:find-select 1] [:find-select 1])]
          (is (= 1 (steps/selected-index (get-in r2 [:state :find :results]))))
          (is (= #{} (get-in r2 [:state :find :results :folded])))
          (is (= [[:reveal-hit]] (:fx r2)))))
      (testing "Enter opens the selected match, and the arrow keys select"
        (is (= 1 (steps/selected-index (get-in (run s [:find-key "ArrowDown" false] [:find-key "ArrowDown" false]) [:state :find :results]))))
        (is (= [[:reveal-hit] [:goto 3]] (:fx (run s [:find-key "Enter" false])))))
      (testing "opening the bar again searches again and keeps the matches until the reply"
        (let [s2 (assoc-in s [:find :results :selected] {:path "src/b.clj" :line 7})
              r2 (run s2 [:command (command :find-in-project)])]
          (is (= :search (:op (second (first (filter #(= :send (first %)) (:fx r2)))))))
          (is (= matches (get-in r2 [:state :find :results :found :matches])))
          (testing "and the selection follows its match, not its place"
            (let [moved [(second matches) {:path "src/c.clj" :line 1 :text "(defn h [])"}]]
              (is (= 0 (steps/selected-index (get-in (run (:state r2) (reply-to r2 :ok {:matches moved :more false}))
                                                     [:state :find :results])))))
            (is (nil? (steps/selected-index (get-in (run (:state r2) (reply-to r2 :ok {:matches (take 1 matches) :more false}))
                                                    [:state :find :results])))))
          (testing "a reply to an older search of the same query is dropped"
            (let [r3 (run (:state r2) [:command (command :find-in-project)])]
              (is (= matches (get-in (run (:state r3) (reply-to r2 :ok {:matches [] :more false}))
                                     [:state :find :results :found :matches])))))))
      (testing "a save searches the project again"
        (let [r2 (run (typed s "(ns a) 1") [:command {:id :save}])
              r3 (run (:state r2) (reply-to r2 :ok {:version "v2"}))]
          (is (some #(= :search (:op (second %))) (filter #(= :send (first %)) (:fx r3))))))
      (testing "a file's matches fold and unfold"
        (is (= #{"src/a.clj"} (get-in (run s [:find-fold "src/a.clj"]) [:state :find :results :folded])))
        (is (= #{} (get-in (run s [:find-fold "src/a.clj"] [:find-fold "src/a.clj"]) [:state :find :results :folded])))))
    (testing "the scope is the commands' to set"
      (is (= :project (get-in (run (:state r) [:find-set :scope :file]) [:state :find :scope]))))
    (testing "a reply to a query that changed since is dropped"
      (let [s (:state (run (:state r) [:find-set :text "def"] (reply-to r :ok {:matches [] :more false})))]
        (is (nil? (get-in s [:find :results :found])))))
    (testing "a new query drops the old results at once"
      (is (= {:searching (get-in r [:state :next-id])}
             (get-in (run (:state r) [:find-set :case true]) [:state :find :results]))))))
