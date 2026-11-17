(ns bare-edit.steps
  "What happens on each event, as pure functions from a state to a new state and effects.

  A step takes a result, `{:state s :fx []}`, and an event, a vector such as
  `[:open-file \"src/a.clj\"]`. It returns the result with the new state and the effects
  to run. Effects are data too:

  - `[:send request]` sends the request on the WebSocket.
  - `[:toast type message]` shows a toast.

  [[bare-edit.app/dispatch!]] stores the state once and then runs the effects, so a step
  never touches the atom, the socket or the page."
  (:require [bare-edit.editor :as editor]
            [bare-edit.model :as model]
            [bare-edit.protocol :as p]))

(def initial-state
  "The state when the page loads, before the connection opens.
  `:next-id` numbers requests, unique within the page, and `:next-buffer` numbers buffers.
  `:pending` maps a request id to its request until the reply arrives. `:buffers` maps a
  buffer id to its buffer, and `:tabs` holds buffer ids in order. `:questions` is the
  queue of open questions; the page shows the first, see [[ask]]. `:conn` is
  `:connecting`, `:open` or `:closed`."
  {:conn :connecting
   :next-id 1
   :next-buffer 1
   :pending {}
   :paths []
   :expanded []
   :buffers {}
   :tabs []
   :active nil
   :questions []})

;; ── Results ─────────────────────────────────────────────────────────────────

(defn result
  "A result holding the state `s` and no effects yet."
  [s]
  {:state s :fx []})

(defn change
  "Returns the result `r` with `f` and `args` applied to its state, as in `update`."
  [r f & args]
  (apply update r :state f args))

(defn toast
  "Returns the result `r` with a toast of `type` showing `message` added to its effects."
  [r type message]
  (update r :fx conj [:toast type message]))

(defn send
  "Returns the result `r` with a request for the operation `op` with `args` to send.
  The request gets the next id and is kept in `:pending` until its reply arrives, so the
  reply can be handled together with it. `then`, when given, is what to do after a reply
  that is `:ok`, such as `[:close-tab id]`. It is kept with the pending request as `:then`
  and never sent, since it belongs to the browser. Without a connection, a toast says so
  instead."
  ([r op args] (send r op args nil))
  ([{s :state :as r} op args then]
   (if (not= :open (:conn s))
     (toast r :error "Not connected to bare-edit. Restart it to keep editing.")
     (let [req (p/request op (:next-id s) args)]
       (-> r
           (change #(-> %
                        (update :next-id inc)
                        (assoc-in [:pending (:id req)] (cond-> req then (assoc :then then)))))
           (update :fx conj [:send req]))))))

;; ── Buffers ─────────────────────────────────────────────────────────────────

(defn buffer-text
  "The text of a buffer, from the editor state in its `:state`, or `\"\"` while it loads."
  [{:keys [state]}]
  (if state (editor/text state) ""))

(defn unsaved?
  "Whether the buffer `b` has text that differs from the disk. A loading buffer has none."
  [b]
  (and (not (:loading? b))
       (not= (buffer-text b) (:disk-text b))))

(defn buffer-ids-for
  "The ids of the buffers in the state `s` that hold the file `path`."
  [s path]
  (keep (fn [[id b]] (when (= path (:path b)) id)) (:buffers s)))

(defn saving?
  "Whether, in the state `s`, a save of `path` is waiting for its reply."
  [s path]
  (some #(and (= :save (:op %)) (= path (:path %))) (vals (:pending s))))

(defn blocked?
  "Whether the state `s` has a question open, so commands wait until it is answered."
  [s]
  (boolean (seq (:questions s))))

(defn ask
  "Returns the state `s` with `question` added to the end of the queue of open questions.
  A question is a map with a `:kind` and the `:buffer` it is about:

  - `{:kind :close :buffer id}` asks whether to save a tab being closed.
  - `{:kind :disk-change :buffer id :disk disk}` asks whether to keep the buffer's text or
    take `disk`, the file's `:text` and `:version`, which changed while it had unsaved
    changes.

  A question of the same kind about the same buffer is replaced where it stands, so a file
  that changes twice is asked about once, with its latest text."
  [s question]
  (let [same? #(and (= (:kind %) (:kind question)) (= (:buffer %) (:buffer question)))]
    (update s :questions (fn [qs] (if (some same? qs)
                                    (mapv #(if (same? %) question %) qs)
                                    (conj qs question))))))

(defn close-buffer
  "Returns the state `s` without the buffer `id`, its tab and the questions about it.
  When it was the active tab, the tab in its place, or else the one before, becomes active."
  [s id]
  (let [tabs (vec (remove #{id} (:tabs s)))
        i    (.indexOf (:tabs s) id)]
    (-> s
        (update :buffers dissoc id)
        (update :questions #(filterv (fn [q] (not= id (:buffer q))) %))
        (assoc :tabs tabs)
        (update :active #(if (= % id) (get tabs (min i (dec (count tabs)))) %)))))

(defn with-disk
  "Returns the buffer `b` with the `:text` of `disk` as its text and its disk text.
  The `:version` of `disk` becomes its disk version. An open buffer takes the text as one
  undoable change, keeping its cursor and undo history. A loading buffer gets its first
  editor state."
  [{:keys [state] :as b} {:keys [text version] :as _disk}]
  (assoc b :disk-text text :disk-version version :loading? false
           :state (if state (editor/replaced state text) (editor/new-state text))))

(defn apply-disk
  "Returns the state `s` after the buffer `id` meets `disk`, the file's `:text` and `:version`.
  See [[bare-edit.model/disk-action]] for what happens. A loading buffer simply loads."
  [s id disk]
  (let [b (get-in s [:buffers id])]
    (if (:loading? b)
      (assoc-in s [:buffers id] (with-disk b disk))
      (case (model/disk-action (unsaved? b) (:disk-version b) (:version disk))
        :none         s
        :reload       (assoc-in s [:buffers id] (with-disk b disk))
        :ask          (ask s {:kind :disk-change :buffer id :disk disk})
        :close        (close-buffer s id)
        :mark-deleted (update-in s [:buffers id] assoc :disk-text "" :disk-version :absent :deleted? true)))))

;; ── Actions shared by several events ────────────────────────────────────────

(defn refresh
  "Returns the result `r` with requests for the path list and the open files from disk.
  Every open file is read in one request, so changes made outside the editor show. This
  runs on connecting and when the window gets focus. Before the connection opens, there is
  nothing to refresh."
  [{s :state :as r}]
  (if (not= :open (:conn s))
    r
    (let [paths (->> (vals (:buffers s)) (remove :loading?) (map :path) distinct vec)]
      (cond-> (send r :paths {})
        (seq paths) (send :read {:paths paths})))))

(defn save-buffer
  "Returns the result `r` with a save of the buffer `id` to send.
  `then` is what to do once the save succeeds, as in [[send]]. A second save of the same
  file is refused with a toast while the first waits for its reply, since it would be sent
  with the old version and conflict; its `then` is dropped with it. A missing or loading
  buffer leaves `r` unchanged."
  ([r id] (save-buffer r id nil))
  ([{s :state :as r} id then]
   (let [b (get-in s [:buffers id])]
     (cond
       (or (nil? b) (:loading? b)) r
       (saving? s (:path b))       (toast r :info (str "Still saving " (model/file-name (:path b)) "."))
       :else (send r :save {:path (:path b) :text (buffer-text b) :version (:disk-version b)}
                   then)))))

(defn close-tab
  "Returns the result `r` with the tab of the buffer `id` closed.
  A buffer with unsaved changes asks first."
  [{s :state :as r} id]
  (if-let [b (get-in s [:buffers id])]
    (if (unsaved? b)
      (change r ask {:kind :close :buffer id})
      (change r close-buffer id))
    r))

(defn step-tab
  "Returns the state `s` with the tab `delta` places from the active one made active.
  The tabs wrap around at either end."
  [s delta]
  (let [{:keys [tabs active]} s]
    (if (seq tabs)
      (assoc s :active (nth tabs (mod (+ delta (.indexOf tabs active)) (count tabs))))
      s)))

;; ── Commands ───────────────────────────────────────────────────────────────

(def commands
  "The editor's commands, as plain data.
  Each has an `:id`, a `:label` and `:keys` in CodeMirror's key syntax, a string or a
  vector of strings. [[bare-edit.keys/key-index]] reads this list, and [[run-command]]
  runs its entries."
  [{:id :save      :label "Save"         :keys "Mod-s"}
   {:id :close-tab :label "Close tab"    :keys "Alt-w"}
   {:id :next-tab  :label "Next tab"     :keys "Alt-]"}
   {:id :prev-tab  :label "Previous tab" :keys "Alt-["}])

(defmulti run-command
  "Returns the result `r` after running `command`, dispatched on its `:id`."
  (fn [_r command] (:id command)))

(defmethod run-command :save      [r _] (if-let [id (:active (:state r))] (save-buffer r id) r))
(defmethod run-command :close-tab [r _] (if-let [id (:active (:state r))] (close-tab r id) r))
(defmethod run-command :next-tab  [r _] (change r step-tab 1))
(defmethod run-command :prev-tab  [r _] (change r step-tab -1))

;; ── Replies ─────────────────────────────────────────────────────────────────

(defmulti on-reply
  "Returns the result `r` after `reply`, which answers the request `req`.
  It is dispatched on the request's `:op`. `:paths` stores the path list. `:read` gives
  each open buffer of a file its disk value; when it fails, the tabs still loading close. `:save` records the sent text as the disk
  text and runs the request's `:then` (`[:close-tab id]` closes the tab, asking again if it
  has unsaved changes by then), or reads the file again on `:conflict`. Any
  other reply that is not `:ok` shows its message as a toast."
  (fn [_r req _reply] (:op req)))

(defmethod on-reply :default [r _req {:keys [status message]}]
  (cond-> r (not= :ok status) (toast :error message)))

(defmethod on-reply :paths [r _req {:keys [status paths message]}]
  (if (= :ok status)
    (change r assoc :paths paths)
    (toast r :error message)))

(defmethod on-reply :read [r {:keys [paths]} {:keys [status files message]}]
  (if (= :ok status)
    (change r (fn [s] (reduce (fn [s [path disk]]
                                (reduce #(apply-disk %1 %2 disk) s (buffer-ids-for s path)))
                              s files)))
    ;; a tab still waiting for this read would wait forever, so it closes
    (-> r
        (change (fn [s] (reduce close-buffer s (for [path paths
                                                     id   (buffer-ids-for s path)
                                                     :when (get-in s [:buffers id :loading?])]
                                                 id))))
        (toast :error message))))

(defmethod on-reply :save [r {:keys [path text then]} {:keys [status version message]}]
  (case status
    ;; the disk now holds the text that was sent, which may differ from the buffer's text
    :ok       (let [[follow-up id] then
                    r (change r (fn [s] (reduce #(update-in %1 [:buffers %2] assoc :disk-text text
                                                            :disk-version version :deleted? false)
                                                s (buffer-ids-for s path))))]
                ;; through close-tab, which asks again if text was typed after the save went out
                (cond-> r (= :close-tab follow-up) (close-tab id)))
    :conflict (send r :read {:paths [path]})       ; the read then asks "keep mine or take disk"
    (toast r :error message)))

;; ── Events ──────────────────────────────────────────────────────────────────

(defmulti step
  "Returns the result `r` after `event`, dispatched on the event's first element.

  - `[:connected]` and `[:disconnected]` follow the WebSocket.
  - `[:focus]` refreshes the path list and the open files.
  - `[:message text]` handles a message from the server.
  - `[:open-file path]` opens a file from the tree, or switches to its tab.
  - `[:select-tab id]` makes a tab active.
  - `[:expand ids]` stores the tree's expanded folders.
  - `[:command command]` runs a command, unless a dialog is open.
  - `[:editor-state old new]` stores an edit in the buffer it started from.
  - `[:answer choice]` answers the first open question, see [[ask]]. A close question takes
    `:save`, `:discard` or `:cancel`, and a changed-on-disk question `:keep` or `:take`."
  (fn [_r event] (first event)))

(defmethod step :connected [r _]
  (-> r (change assoc :conn :open) refresh))

(defmethod step :disconnected [r _]
  (-> r
      (change assoc :conn :closed :pending {})
      (toast :error "Disconnected from bare-edit. Restart it to keep editing.")))

(defmethod step :focus [r _]
  (refresh r))

(defmethod step :message [r [_ text]]
  (let [msg (p/decode text)]
    (case (p/kind msg)
      :reply (if-let [req (get-in r [:state :pending (:id msg)])]
               (-> r (change update :pending dissoc (:id msg)) (on-reply req msg))
               r)
      :event (cond-> r (= :out (:event msg)) (toast :info (:text msg)))
      r)))

(defmethod step :open-file [{s :state :as r} [_ path]]
  (cond
    (not (some #{path} (:paths s)))             r      ; a folder in the tree
    (first (buffer-ids-for s path))             (change r assoc :active (first (buffer-ids-for s path)))
    :else (let [id (:next-buffer s)]
            (-> r
                (change #(-> %
                             (update :next-buffer inc)
                             (assoc-in [:buffers id] {:id id :path path :loading? true})
                             (update :tabs conj id)
                             (assoc :active id)))
                (send :read {:paths [path]})))))

(defmethod step :select-tab [r [_ id]]
  (change r assoc :active id))

(defmethod step :expand [r [_ expanded]]
  (change r assoc :expanded expanded))

(defmethod step :command [r [_ command]]
  (if (blocked? (:state r)) r (run-command r command)))

(defmethod step :editor-state [r [_ old new]]
  ;; stored in the buffer holding the state the transaction started from; when none does (it
  ;; closed, or disk replaced its text), the edit is dropped and the view gets the buffer's
  ;; state back
  (change r (fn [s]
              (if-let [id (some (fn [[id b]] (when (identical? old (:state b)) id)) (:buffers s))]
                (assoc-in s [:buffers id :state] new)
                s))))

(defmethod step :answer [{s :state :as r} [_ choice]]
  (if-let [{:keys [kind buffer disk]} (first (:questions s))]
    (let [r (change r update :questions #(vec (rest %)))]
      (case kind
        :close       (case choice
                       :save    (save-buffer r buffer [:close-tab buffer])
                       :discard (change r close-buffer buffer)
                       :cancel  r)
        :disk-change (change r update-in [:buffers buffer]
                             (fn [b] (case choice
                                       :keep (assoc b :disk-text (:text disk) :disk-version (:version disk))
                                       :take (with-disk b disk))))))
    r))
