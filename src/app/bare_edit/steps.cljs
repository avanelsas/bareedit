(ns bare-edit.steps
  "What happens on each event, as pure functions from a state to a new state and effects.

  A step takes a result, `{:state s :fx []}`, and an event, a vector such as
  `[:open-file \"src/a.clj\"]`. It returns the result with the new state and the effects
  to run. Effects are data too:

  - `[:send request]` sends the request on the WebSocket.
  - `[:toast type message]` shows a toast.
  - `[:goto line]` moves the cursor to the start of `line` in the editor.
  - `[:focus-find]` gives the find bar's query field the focus.
  - `[:find-in-editor id query]` runs the find command `id` with `query` on the editor.
  - `[:reveal-hit]` scrolls the selected match of the project search into view.
  - `[:completed token candidates]` answers the editor's completion request `token`.

  [[bare-edit.app/dispatch!]] stores the state once and then runs the effects, so a step
  never touches the atom, the socket or the page."
  (:require [bare-edit.editor :as editor]
            [bare-edit.model :as model]
            [bare-edit.protocol :as p]
            [bare-edit.search :as search]
            [bare-edit.settings :as settings]
            [clojure.string :as str]))

(def initial-state
  "The state when the page loads, before the connection opens.
  `:next-id` numbers requests, unique within the page, and `:next-buffer` numbers buffers.
  `:pending` maps a request id to its request until the reply arrives. `:buffers` maps a
  buffer id to its buffer, and `:tabs` holds buffer ids in order. A buffer's `:path` is a
  project path, or `:settings` for the settings file. `:questions` is the
  queue of open questions; the page shows the first, see [[ask]]. `:palette` is the open
  palette, `{:kind :query}` with `:kind` `:files` or `:commands`, or `nil`. `:results`
  holds the entries of the results column, newest first, see [[add-entry]].
  `:results-open?` says whether the column shows, and `:unfolded` holds the ids of the
  entries shown in full. `:conn` is `:connecting`, `:open` or `:closed`. `:config` is the
  user's look and feel, `{:preset :font-family :font-size}` from the server, or `nil` until
  it arrives, and `:project` the project folder's name, the same way. `:read-only?` says
  whether the server refuses every change to the project's files. `:repl` holds the
  last thing heard from the REPL of each session kind, see [[record-repl]]. `:unreadable`
  holds the folders the last path list could not read. `:find` is the open find bar, see
  [[find-defaults]], or `nil`."
  {:conn :connecting
   :config nil
   :project nil
   :read-only? false
   :repl {}
   :next-id 1
   :next-buffer 1
   :pending {}
   :paths []
   :unreadable []
   :expanded []
   :buffers {}
   :tabs []
   :active nil
   :questions []
   :palette nil
   :find nil
   :results []
   :results-open? true
   :unfolded #{}})

(def effect-kinds
  "Every kind of effect a step can return, as the first element of the effect.
  `[:send request]` sends a request, `[:toast type message]` shows a toast,
  `[:focus-editor]` gives the editor the keyboard focus, and `[:edit id]` runs the editor
  command `id` on the editor, see [[bare-edit.editor/run-command!]]. `[:goto line]` moves
  the cursor to `line` in the active buffer and gives the editor the focus.
  `[:completed token candidates]` answers the completion request `token` of the editor
  with nREPL's candidates, or with `nil` for the words of the open file. `[:focus-find]`
  gives the find bar's query field the focus. `[:find-in-editor id query]` runs the find
  command `id` with `query` on the editor, see [[bare-edit.editor/find-commands]].
  `[:reveal-hit]` scrolls the selected match of the project search into view."
  #{:send :toast :focus-editor :edit :goto :completed :focus-find :find-in-editor :reveal-hit})

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

(defn connected?
  "Whether the state `s` has an open connection to the server."
  [s]
  (= :open (:conn s)))

(defn send
  "Returns the result `r` with a request for the operation `op` with `args` to send.
  The request gets the next id and is kept in `:pending` until its reply arrives, so the
  reply can be handled together with it. `local`, when given, is a map of keys that belong
  to the browser, such as a save's `:then` or a completion's `:token`. They are kept with
  the pending request and never sent; the reply of each operation reads its own. Without a
  connection, a toast says so instead."
  ([r op args] (send r op args nil))
  ([{s :state :as r} op args local]
   (if-not (connected? s)
     (toast r :error "Not connected to bareedit. Restart it to keep editing.")
     (let [req (p/request op (:next-id s) args)]
       (-> r
           (change #(-> %
                        (update :next-id inc)
                        (assoc-in [:pending (:id req)] (merge req local))))
           (update :fx conj [:send req]))))))

;; ── Buffers ─────────────────────────────────────────────────────────────────

(defn buffer-text
  "The text of a buffer, from the editor state in its `:state`, or `\"\"` while it loads."
  [{:keys [state]}]
  (if state (editor/text state) ""))

(defn unsaved?
  "Whether the buffer `b` has text that differs from the disk. A loading buffer has none.
  The page asks this of every tab on every render, so it is cheap: see
  [[bare-edit.editor/same-text?]]."
  [{:keys [state disk-text] :as b}]
  (and (not (:loading? b))
       (if state
         (not (editor/same-text? state disk-text))
         (not= "" disk-text))))

(defn buffer-ids-for
  "The ids of the buffers in the state `s` that hold the file `path`."
  [s path]
  (keep (fn [[id b]] (when (= path (:path b)) id)) (:buffers s)))

(defn question-problem
  "Why the path typed in `question`, the first in the state `s`, cannot be used, or `nil`.
  The answer is a sentence. A new file's path must pass [[bare-edit.model/path-problem]].
  A rename's must too, and it must not name another file in the project or a file open in
  a tab. Its own path is fine, and renames nothing."
  [s {:keys [kind buffer path]}]
  (or (model/path-problem path)
      (when (= :rename kind)
        (let [to (model/normalize-path path)]
          (when (not= to (get-in s [:buffers buffer :path]))
            (cond (some #{to} (:paths s))       "A file with that path exists."
                  (seq (buffer-ids-for s to))   "That file is open in a tab."))))))

(defn- rename-buffers
  "Returns the state `s` with every buffer of the file `from` holding the file `to`.
  The buffers keep their text, their unsaved changes and their undo history. When `to`
  is in another language, such as `.md` renamed to `.clj`, their editor states change to
  it, see [[bare-edit.model/language-of]]."
  [s from to]
  (let [language (model/language-of to)
        moved    (fn [b] (cond-> (assoc b :path to)
                           (and (:state b) (not= language (model/language-of from)))
                           (update :state editor/with-language language)))]
    (reduce #(update-in %1 [:buffers %2] moved) s (buffer-ids-for s from))))

(defn busy?
  "Whether, in the state `s`, a save or a rename of the file `path` waits for its reply.
  A busy file is not saved or renamed again until then: the second request would carry
  the version from before the first, or the path the first moves away from."
  [s path]
  (some #(case (:op %)
           :save   (= path (:path %))
           :rename (= path (:from %))
           false)
        (vals (:pending s))))

(defn- busy-toast
  "Returns the result `r` with a toast saying the file `path` is still being saved or renamed."
  [r path]
  (toast r :info (str "Still saving or renaming " (model/file-name path) ".")))

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
  - `{:kind :new-file :path path}` asks for the path of a new file, about no buffer.
  - `{:kind :rename :buffer id :path path}` asks for the new path of the buffer's file,
    starting from its own.

  A question of the same kind about the same buffer is replaced where it stands, so a file
  that changes twice is asked about once, with its latest text. Asking closes an open
  palette, so the question is the only thing that takes keys."
  [s question]
  (let [same? #(and (= (:kind %) (:kind question)) (= (:buffer %) (:buffer question)))]
    (-> s
        (assoc :palette nil)
        (update :questions (fn [qs] (if (some same? qs)
                                      (mapv #(if (same? %) question %) qs)
                                      (conj qs question)))))))

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
           :state (if state
                    (editor/replaced state text)
                    (editor/new-state text (model/language-of (:path b))))))

(defn apply-disk
  "Returns the state `s` after the buffer `id` meets `disk`, the file's `:text` and `:version`.
  See [[bare-edit.model/disk-action]] for what happens. A loading buffer simply loads, but
  only from a `disk` with `:text`: one without, a reply to a read that knew the version,
  leaves it to wait for its own read."
  [s id disk]
  (let [b (get-in s [:buffers id])]
    (if (:loading? b)
      (cond-> s (contains? disk :text) (assoc-in [:buffers id] (with-disk b disk)))
      (case (model/disk-action (unsaved? b) (:disk-version b) (:version disk))
        :none         s
        :reload       (assoc-in s [:buffers id] (with-disk b disk))
        :ask          (ask s {:kind :disk-change :buffer id :disk disk})
        :close        (close-buffer s id)
        :mark-deleted (update-in s [:buffers id] assoc :disk-text "" :disk-version :absent :deleted? true)))))

;; ── The results column ──────────────────────────────────────────────────────

(def max-results
  "How many entries the results column keeps. Older ones are dropped."
  100)

(defn add-entry
  "Returns the state `s` with `entry` at the top of the results column.
  An entry records what happened, as a map with an `:id` and a `:kind`:

  - `:eval` is an evaluation, with the `:path` and `:line` of its code, its `:form`, and the
    reply's `:status`, `:out`, `:err`, `:value` and `:message` once it arrives.
  - `:doc` is a docs lookup of `:sym`, with the reply's `:info`.
  - `:out` is output that belongs to no evaluation, in `:out`.

  An entry whose request waits for its reply has the status `:running`. Only the newest
  [[max-results]] entries are kept."
  [s entry]
  (update s :results #(into [entry] (take (dec max-results)) %)))

(defn show-results
  "Returns the state `s` with the results column shown.
  A new entry shows it, so the result of a command is never hidden."
  [s]
  (assoc s :results-open? true))

(defn update-entry
  "Returns the state `s` with `f` and `args` applied to the results entry `id`, if it is there."
  [s id f & args]
  (update s :results (fn [rs] (mapv #(if (= id (:id %)) (apply f % args) %) rs))))

(defn record-repl
  "Returns the state `s` with what an evaluation of the file at `path` says about its REPL.
  `reply` gives the `:status`, such as `:running` while it waits, and the `:message`. It is
  the last thing heard, from a send or a reply, so a reply to an older evaluation can come
  after a newer one started. It is kept per session kind under `:repl`, apart from the
  results, so clearing the results keeps it."
  [s path {:keys [status message]}]
  (if-let [kind (p/kind-for path)]
    (assoc-in s [:repl kind] {:status status :message message})
    s))

(defn- send-with-entry
  "Returns the result `r` with a request for `op` with `args` to send, and `entry` for it.
  The entry gets the request's id and the status `:running`, see [[add-entry]], and the
  column shows. An evaluation marks its REPL `:running` too, see [[record-repl]]. Without a
  connection, [[send]] adds its toast and there is no entry."
  [{s :state :as r} op args entry]
  (if (connected? s)
    (-> r
        (send op args)
        (change #(cond-> (-> % (add-entry (assoc entry :id (:next-id s) :status :running)) show-results)
                   (#{:eval :load-file} op) (record-repl (:path args) {:status :running}))))
    (send r op args)))

(defn- active-buffer
  "The active buffer of the state `s`, when it is loaded, or `nil`."
  [s]
  (let [b (get-in s [:buffers (:active s)])]
    (when (:state b) b)))

(def ^:private settings-refusal
  "The toast for a command that works on project files only, run on the settings file."
  [:toast :info "The settings file is not in the project, so it is not renamed or evaluated."])

(defn- project-only
  "Returns the result `r` after `f`, or with a toast when the active tab is the settings file.
  It guards the commands that work on project files only: rename and evaluation."
  [{s :state :as r} f]
  (if (model/settings? (:path (active-buffer s)))
    (update r :fx conj settings-refusal)
    (f r)))

(defn evaluate
  "Returns the result `r` with the form that `form-at` finds in the active buffer evaluated.
  `form-at` is a function from an `EditorState` to `{:code :line}` or `nil`, such as
  [[bare-edit.editor/form-at]]. The form is evaluated in the namespace of the buffer's `ns`
  form. With no form there, nothing happens."
  [{s :state :as r} form-at]
  (let [{:keys [path state] :as b} (active-buffer s)]
    (if-let [{:keys [code line]} (some-> b :state form-at)]
      (send-with-entry r :eval {:path path :ns (model/ns-of (editor/text state)) :line line :code code}
                       {:kind :eval :path path :line line :form code})
      r)))

(defn newest-running
  "The id of the newest evaluation in the state `s` that waits for its reply, or `nil`."
  [s]
  (some #(when (and (= :eval (:kind %)) (= :running (:status %))) (:id %)) (:results s)))

(defn interrupt
  "Returns the result `r` with an interrupt of the evaluation `id` to send.
  With no `id`, there is nothing to interrupt and `r` is returned as it is."
  [r id]
  (cond-> r id (send :interrupt {:eval id})))

;; ── Actions shared by several events ────────────────────────────────────────

(defn- known-versions
  "The disk version the state `s` holds for each of `paths` whose buffers have all loaded.
  The server leaves out the text of a file still at that version."
  [s paths]
  (into {} (for [path paths
                 :let [bs (map #(get-in s [:buffers %]) (buffer-ids-for s path))]
                 :when (and (seq bs) (not-any? :loading? bs) (some? (:disk-version (first bs))))]
             [path (:disk-version (first bs))])))

(defn read-paths
  "Returns the result `r` with reads of `paths` to send.
  The project files go in one `:read`, with the versions already held, see
  [[known-versions]], and `:settings`, the settings file, goes in a `:read-settings`, since
  it is not in the project."
  [{s :state :as r} paths]
  (let [{settings true files false} (group-by model/settings? paths)]
    (cond-> r
      (seq files)    (send :read {:paths (vec files) :known (known-versions s files)})
      (seq settings) (send :read-settings {}))))

(defn refresh
  "Returns the result `r` with requests for the path list and the open files from disk.
  Every open file is read in one request, so changes made outside the editor show. This
  runs on connecting and when the window gets focus. Before the connection opens, there is
  nothing to refresh."
  [{s :state :as r}]
  (if-not (connected? s)
    r
    (let [paths (->> (vals (:buffers s)) (remove :loading?) (map :path) distinct vec)]
      (cond-> (send r :paths {})
        (seq paths) (read-paths paths)))))

(defn save-buffer
  "Returns the result `r` with a save of the buffer `id` to send.
  `then` is what to do once the save succeeds, such as `[:close-tab id]`, kept with the
  pending request as `:then`, see [[send]]. The settings file is saved with
  `:save-settings`, and its pending request keeps `:path :settings`. A save of a [[busy?]] file is refused with a
  toast, and its `then` is dropped with it. A missing or loading buffer leaves `r`
  unchanged."
  ([r id] (save-buffer r id nil))
  ([{s :state :as r} id then]
   (let [b (get-in s [:buffers id])]
     (cond
       (or (nil? b) (:loading? b)) r
       (busy? s (:path b))         (busy-toast r (:path b))
       (model/settings? (:path b)) (send r :save-settings {:text (buffer-text b) :version (:disk-version b)}
                                         (cond-> {:path :settings} then (assoc :then then)))
       :else (send r :save {:path (:path b) :text (buffer-text b) :version (:disk-version b)}
                   (when then {:then then}))))))

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

(def command-groups
  "The groups of the command palette, in the order it shows them, each with its header."
  [{:id :file      :label "File"}
   {:id :tabs      :label "Tabs"}
   {:id :repl      :label "REPL"}
   {:id :results   :label "Results"}
   {:id :edit      :label "Edit"}
   {:id :structure :label "Structure"}
   {:id :settings  :label "Settings"}])

(def group-labels
  "The header of each group in [[command-groups]], by its id."
  (into {} (map (juxt :id :label)) command-groups))

(def group-order
  "The place of each group in [[command-groups]], by its id, from 0."
  (into {} (map-indexed (fn [i g] [(:id g) i])) command-groups))

(def commands
  "The editor's commands, as plain data.
  Each has an `:id`, a `:label` and optional `:keys` in CodeMirror's key syntax, a string or
  a vector of strings. A command is in the command palette exactly when it has a
  `:group`, one of [[command-groups]], under whose header it shows, in the order of this
  list. The commands that open and close the palette have none.
  A command with `:scope :editor` works on the text, so its keys act only while the editor
  has the focus. A command with `:clojure true` works on Clojure forms only, see
  [[clojure-only]]. The commands the editor itself runs are those of
  [[bare-edit.editor/command?]].
  [[bare-edit.keys/key-index]] reads this list, the command palette shows it, and
  [[run-command]] runs its entries."
  [{:id :file-palette      :group :file      :label "Go to file"              :keys "Mod-p"}
   {:id :new-file          :group :file      :label "New file"}
   {:id :rename-file       :group :file      :label "Rename file"             :keys "F2"}
   {:id :save              :group :file      :label "Save"                    :keys "Mod-s"}
   {:id :save-all          :group :file      :label "Save all"                :keys "Mod-Alt-s"}
   {:id :refresh-file-tree :group :file      :label "Refresh file tree"}
   {:id :close-tab         :group :tabs      :label "Close tab"               :keys "Alt-w"}
   {:id :next-tab          :group :tabs      :label "Next tab"                :keys "Alt-]"}
   {:id :prev-tab          :group :tabs      :label "Previous tab"            :keys "Alt-["}
   {:id :eval-form         :group :repl      :label "Evaluate form"           :keys "Mod-Enter"       :scope :editor}
   {:id :eval-top-form     :group :repl      :label "Evaluate top-level form" :keys "Mod-Shift-Enter" :scope :editor}
   {:id :eval-file         :group :repl      :label "Evaluate file"}
   {:id :interrupt         :group :repl      :label "Interrupt evaluation"    :keys "Mod-."}
   {:id :show-docs         :group :repl      :label "Show docs"}
   {:id :clear-results     :group :results   :label "Clear results"}
   {:id :toggle-results    :group :results   :label "Toggle results column"}
   {:id :find              :group :edit      :label "Find and replace"        :keys "Mod-f"}
   {:id :find-in-project   :group :edit      :label "Find in project"         :keys "Mod-Shift-f"}
   {:id :find-next         :group :edit      :label "Find next"               :keys ["F3" "Mod-g"]}
   {:id :find-previous     :group :edit      :label "Find previous"           :keys ["Shift-F3" "Mod-Shift-g"]}
   {:id :toggle-comment    :group :edit      :label "Toggle comment"          :keys "Mod-/"           :scope :editor}
   {:id :show-completions  :group :edit      :label "Show completions"        :keys "Ctrl-Space"      :scope :editor}
   {:id :reindent          :group :edit      :label "Reindent the current form"                       :scope :editor :clojure true}
   {:id :slurp-forward     :group :structure :label "Slurp forward"           :keys "Ctrl-Shift-0"    :scope :editor :clojure true}
   {:id :slurp-backward    :group :structure :label "Slurp backward"          :keys "Ctrl-Shift-9"    :scope :editor :clojure true}
   {:id :barf-forward      :group :structure :label "Barf forward"            :keys "Ctrl-Shift-]"    :scope :editor :clojure true}
   {:id :barf-backward     :group :structure :label "Barf backward"           :keys "Ctrl-Shift-["    :scope :editor :clojure true}
   {:id :unwrap            :group :structure :label "Unwrap"                  :keys "Alt-s"           :scope :editor :clojure true}
   {:id :kill              :group :structure :label "Kill to end of form"     :keys "Ctrl-k"          :scope :editor :clojure true}
   {:id :expand-selection  :group :structure :label "Expand selection"        :keys "Alt-ArrowUp"     :scope :editor :clojure true}
   {:id :shrink-selection  :group :structure :label "Shrink selection"        :keys "Alt-ArrowDown"   :scope :editor :clojure true}
   {:id :open-settings     :group :settings  :label "Open settings"}
   {:id :command-palette   :label "Show all commands" :keys ["Mod-Shift-p" "F1"]}
   {:id :close             :label "Close the palette or the find bar" :keys "Escape"}])

(defn command-dispatch
  "The value [[run-command]] dispatches `command` on.
  It is `::editor` for a command the editor runs, see [[bare-edit.editor/command?]], since
  all of those run the same way, and otherwise the command's `:id`."
  [_r command]
  (if (editor/command? (:id command)) ::editor (:id command)))

(defmulti run-command
  "Returns the result `r` after running `command`, dispatched by [[command-dispatch]]."
  command-dispatch)

(def clojure-only
  "The ids of the commands that work on Clojure forms only.
  A file in another language refuses them with a toast."
  (into #{} (comp (filter :clojure) (map :id)) commands))

(defmethod run-command ::editor [{s :state :as r} {:keys [id]}]
  (let [{:keys [state path]} (get-in s [:buffers (:active s)])]
    (cond
      ;; on the editor only when it shows a loaded buffer
      (nil? state)                                             r
      (and (clojure-only id) (not= :clojure (model/language-of path)))
      (toast r :info "This command works in Clojure files only.")
      :else                                                    (update r :fx conj [:edit id]))))

(defmethod run-command :save      [r _] (if-let [id (:active (:state r))] (save-buffer r id) r))
(defmethod run-command :close-tab [r _] (if-let [id (:active (:state r))] (close-tab r id) r))
(defmethod run-command :next-tab  [r _] (change r step-tab 1))
(defmethod run-command :prev-tab  [r _] (change r step-tab -1))

(defmethod run-command :save-all [{s :state :as r} _]
  (reduce save-buffer r (for [id (:tabs s) :when (unsaved? (get-in s [:buffers id]))] id)))

(def command-by-name
  "The [[commands]] by the name of their `:id`, as a palette item gives it back."
  (into {} (map (juxt (comp name :id) identity)) commands))

(defn- close-palette
  "Returns the result `r` with the palette closed and the editor given the focus."
  [r]
  (-> r (change assoc :palette nil) (update :fx conj [:focus-editor])))

(defn- open-palette
  "Returns the result `r` with the palette of `kind` open, with an empty query.
  The page makes a new palette for each kind, so switching kind starts empty too. The
  palette of `kind` that is open already stays as it is."
  [{s :state :as r} kind]
  (if (= kind (get-in s [:palette :kind]))
    r
    (change r assoc :palette {:kind kind :query ""})))

(defmethod run-command :file-palette    [r _] (open-palette r :files))
(defmethod run-command :command-palette [r _] (open-palette r :commands))

(defmethod run-command :close [{s :state :as r} _]
  (cond (:palette s) (close-palette r)
        (:find s)    (-> r (change assoc :find nil) (update :fx conj [:focus-editor]))
        :else        r))

(defmethod run-command :new-file [{s :state :as r} _]
  (change r ask {:kind :new-file
                 :path (model/folder-of (get-in s [:buffers (:active s) :path]))}))

(defmethod run-command :rename-file [r _]
  (project-only r (fn [{s :state :as r}]
                    (if-let [{:keys [id path]} (active-buffer s)]
                      (change r ask {:kind :rename :buffer id :path path})
                      r))))

(defmethod run-command :refresh-file-tree [r _] (send r :paths {}))

(defmethod run-command :eval-form      [r _] (project-only r #(evaluate % editor/form-at)))
(defmethod run-command :eval-top-form  [r _] (project-only r #(evaluate % editor/top-level-form-at)))
(defmethod run-command :interrupt      [r _] (interrupt r (newest-running (:state r))))
(defmethod run-command :clear-results  [r _] (change r assoc :results [] :unfolded #{}))
(defmethod run-command :toggle-results [r _] (change r update :results-open? not))

(defmethod run-command :eval-file [r _]
  (project-only r (fn [{s :state :as r}]
                    (if-let [{:keys [path state]} (active-buffer s)]
                      (send-with-entry r :load-file {:path path :text (editor/text state)}
                                       {:kind :eval :path path :line 1
                                        :form (str "(load-file \"" path "\")")})
                      r))))

(defmethod run-command :show-docs [r _]
  (project-only r (fn [{s :state :as r}]
                    (let [{:keys [path state]} (active-buffer s)]
                      (if-let [sym (some-> state editor/symbol-at)]
                        (send-with-entry r :lookup {:path path :sym sym :ns (model/ns-of (editor/text state))}
                                         {:kind :doc :path path :sym sym})
                        r)))))

;; ── Replies ─────────────────────────────────────────────────────────────────

(defmulti on-reply
  "Returns the result `r` after `reply`, which answers the request `req`.
  It is dispatched on the request's `:op`. `:paths` stores the path list. `:read` gives
  each open buffer of a file its disk value; when it fails, the tabs still loading close.
  `:save` records the sent text as the disk text and runs the request's `:then`
  (`[:close-tab id]` closes the tab, asking again if it has unsaved changes by then), and a
  save that created the file asks for the path list again. It reads the file again on
  `:conflict`. `:read-settings` and `:save-settings` do the same for the settings file, and
  a saved settings file asks for `:config` again. An `:eval`, `:load-file` or `:lookup` reply fills in its results entry,
  whatever its status. A `:completions` reply answers the editor's request with the
  candidates, or with `nil` when it is not `:ok`. A `:config` reply stores the look and
  feel and shows each problem as a warning toast. An `:interrupt` reply shows its message,
  if any, as a toast. Any other reply that is not `:ok` shows its message as a toast."
  (fn [_r req _reply] (:op req)))

(defmethod on-reply :default [r _req {:keys [status message]}]
  (cond-> r (not= :ok status) (toast :error message)))

(defmethod on-reply :config [r _req {:keys [status config problems message]}]
  (if (= :ok status)
    (reduce #(toast %1 :warning %2) (change r assoc :config config) problems)
    (toast r :error message)))

(defmethod on-reply :project [r _req {:keys [status name read-only]}]
  (cond-> r (= :ok status) (change assoc :project name :read-only? (true? read-only))))

(defn unreadable-text
  "The message for `folders`, the project-relative folders the path list could not read.
  It names at most three, and `\".\"` is the project folder itself."
  [folders]
  (let [n     (count folders)
        shown (take 3 folders)
        more  (- n (count shown))]
    (if (= ["."] folders)
      "Could not read the project folder."
      (str "Could not read " (if (= 1 n) "the folder " (str n " folders: "))
           (str/join ", " shown) (when (pos? more) (str " and " more " more")) ". "
           (if (= 1 n) "It is" "They are") " left out of the file tree."))))

(defmethod on-reply :paths [r _req {:keys [status paths unreadable message]}]
  (if (= :ok status)
    (cond-> (change r assoc :paths paths :unreadable unreadable)
      (and (seq unreadable) (not= unreadable (get-in r [:state :unreadable])))
      (toast :warning (unreadable-text unreadable)))
    (toast r :error message)))

(defn- loaded-gotos
  "Returns the result `r` with the cursor moved for each buffer that loaded with a `:goto` line.
  The line is dropped from the buffer, and the cursor moves only in the active buffer,
  since the editor shows that one."
  [{s :state :as r}]
  (reduce (fn [r [id {:keys [goto]}]]
            (cond-> (change r update-in [:buffers id] dissoc :goto)
              (= id (:active s)) (update :fx conj [:goto goto])))
          r
          (for [[id b] (:buffers s) :when (and (:goto b) (not (:loading? b)))] [id b])))

(defn- disk-read
  "Returns the result `r` after a read of `paths` from disk, with `status` and `message`.
  When it is `:ok`, `files` maps each path to `{:text :version}`, and each open buffer of a
  path takes its disk value. Otherwise the tabs still loading one of `paths` close, since
  they would wait forever, and a toast shows `message`."
  [r paths status files message]
  (if (= :ok status)
    (-> r
        (change (fn [s] (reduce (fn [s [path disk]]
                                  (reduce #(apply-disk %1 %2 disk) s (buffer-ids-for s path)))
                                s files)))
        loaded-gotos)
    (-> r
        (change (fn [s] (reduce close-buffer s (for [path paths
                                                     id   (buffer-ids-for s path)
                                                     :when (get-in s [:buffers id :loading?])]
                                                 id))))
        (toast :error message))))

(defmethod on-reply :read [r {:keys [paths]} {:keys [status files message]}]
  (disk-read r paths status files message))

(defmethod on-reply :read-settings [r _req {:keys [status text version message]}]
  ;; a file still to create shows every setting with the default preset's value
  (let [text (if (= :absent version) (settings/default-text) text)]
    (disk-read r [:settings] status {:settings {:text text :version version}} message)))

(defn- fill-entry
  "Returns the result `r` with `reply` merged into the results entry of the same id."
  [r reply]
  (change r update-entry (:id reply) merge (dissoc reply :id)))

(defn- evaluated
  "Returns the result `r` after `reply` to the evaluation of the file at `path`.
  It fills in the results entry and records what the reply says about the REPL."
  [r path reply]
  (-> r (fill-entry reply) (change record-repl path reply)))

(defmethod on-reply :eval      [r {:keys [path]} reply] (evaluated r path reply))
(defmethod on-reply :load-file [r {:keys [path]} reply] (evaluated r path reply))
(defmethod on-reply :lookup    [r _req reply] (fill-entry r reply))

(defmethod on-reply :completions [r {:keys [token]} {:keys [status candidates]}]
  ;; any status but :ok answers nil, so the editor offers the words of the file instead
  (update r :fx conj [:completed token (when (= :ok status) candidates)]))

(defmethod on-reply :interrupt [r _req {:keys [status message]}]
  (cond-> r message (toast (if (= :ok status) :info :error) message)))

(defmethod on-reply :rename [r {:keys [from to]} {:keys [status message]}]
  (case status
    :ok       (-> r
                  (change rename-buffers from to)
                  (send :paths {})
                  (toast :info (str "Renamed " (model/file-name from) " to " (model/file-name to) ".")))
    ;; the read then says what is on disk: changed, or gone
    :conflict (-> r (toast :error message) (read-paths [from]))
    (toast r :error message)))

(defn- refresh-project
  "Returns the result `r` with the project searched again for the find bar's query.
  The matches found stay until the reply replaces them. The results' `:searching`
  becomes the request's id, so only the reply to the newest search counts. A search is
  sent only when the find bar searches the project and its query has text."
  [{s :state :as r}]
  (let [{:keys [scope query]} (:find s)]
    (if (and (= :project scope) (seq (:text query)))
      (-> r
          (change assoc-in [:find :results :searching] (:next-id s))
          (send :search {:query query}))
      r)))

(defn- saved
  "Returns the result `r` after a save of `text` to `path`, sent with the version `sent-version`.
  `then` is the save's follow-up, and `status`, `version` and `message` come from the reply.
  The disk text of each buffer of `path` becomes `text`, a new project file adds itself to
  the path list, and `[:close-tab id]` closes that tab. A conflict reads the file again,
  which then asks whether to keep the buffer or take the disk."
  [r {:keys [path text then] sent-version :version} {:keys [status version message]}]
  (case status
    ;; the disk now holds the text that was sent, which may differ from the buffer's text
    :ok       (let [[follow-up id] then
                    r (change r (fn [s] (reduce #(update-in %1 [:buffers %2] assoc :disk-text text
                                                            :disk-version version :deleted? false)
                                                s (buffer-ids-for s path))))]
                (cond-> r
                  ;; a save that created the file adds it to the path list
                  (and (= :absent sent-version) (not (model/settings? path))) (send :paths {})
                  ;; through close-tab, which asks again if text was typed after the save went out
                  (= :close-tab follow-up) (close-tab id)))
    :conflict (read-paths r [path])
    (toast r :error message)))

(defmethod on-reply :save [r req reply] (saved r req reply))

(defmethod on-reply :save-settings [r req {:keys [status] :as reply}]
  ;; a saved settings file changes the look at once
  (cond-> (saved r req reply)
    (= :ok status) (send :config {})))

(defmethod on-reply :search [{s :state :as r} _req {:keys [id status matches more message]}]
  ;; only the reply to the newest search counts, see refresh-project
  (if (= id (get-in s [:find :results :searching]))
    (change r assoc-in [:find :results :found] (if (= :ok status) {:matches matches :more more} {:problem message}))
    r))

;; ── Events ──────────────────────────────────────────────────────────────────

(defmulti step
  "Returns the result `r` after `event`, dispatched on the event's first element.

  - `[:connected]` and `[:disconnected]` follow the WebSocket.
  - `[:focus]` refreshes the path list and the open files.
  - `[:page-config config]` stores the look and feel the page was served with.
  - `[:message text]` handles a message from the server.
  - `[:open-file path]` opens a file from the tree, or switches to its tab.
  - `[:select-tab id]` makes a tab active.
  - `[:close-tab id]` closes a tab, asking first when it has unsaved changes.
  - `[:expand ids]` stores the tree's expanded folders.
  - `[:command command]` runs a command, unless a dialog is open.
  - `[:editor-state old new]` stores an edit in the buffer it started from.
  - `[:answer choice]` answers the first open question, see [[ask]]. A close question takes
    `:save`, `:discard` or `:cancel`, a changed-on-disk question `:keep` or `:take`, a
    new-file question `:create` or `:cancel`, and a rename question `:rename` or `:cancel`.
  - `[:question-path text]` stores the path typed in a new-file or rename question.
  - `[:palette-query text]` stores the query typed in the open palette.
  - `[:palette-pick id]` runs what was picked: a file opens, a command runs. Either way the
    palette closes and the editor gets the focus first, unless the pick opened a palette.
    Nothing runs while a question is open.
  - `[:palette-close]` closes the palette, as on a click outside it. The palette never
    closes itself: the page refuses its own close and its own pick, so the state alone
    decides whether it is open.
  - `[:interrupt id]` interrupts the evaluation of the results entry `id`.
  - `[:unfold id]` shows the results entry `id` in full, or folds it again.
  - `[:complete {:prefix :token}]` asks for the completions of `prefix`, which the editor
    waits for as `token`. A Clojure file in the active tab asks the REPL, in the file's
    namespace; any other file, or no connection, answers `nil` at once.
  - `[:jump-to id]` goes to the code of the results entry `id`: to its line, if it has one,
    when its file is open, or else it opens the file.
  - `[:find-set k v]` sets the option or text `k` of the open find bar's query to `v`,
    one of the keys of [[bare-edit.search/blank-query]], and searches the project again
    in the project scope.
  - `[:find-replacement text]` sets the text that replaces a match in the file.
  - `[:find-open i]` opens the match `i` of the project search at its line.
  - `[:find-select delta]` selects the match `delta` places from the selected one in the
    project search, without opening it.
  - `[:find-key key shift?]` is a key of [[find-keys]] pressed in the query field, with
    or without Shift, see [[find-key]].
  - `[:find-fold path]` folds the matches of the file `path` in the project search, or
    shows them again.
  - `[:find-edit id]` runs the find command `id`, `:replace-match` or `:replace-all`, with
    the find bar's query on the active buffer, when the bar searches the file."
  (fn [_r event] (first event)))

(defmethod step :connected [{s :state :as r} _]
  ;; each is asked for until it arrives, so the look and feel's problems show once
  (cond-> (-> r (change assoc :conn :open) refresh)
    (nil? (:config s))  (send :config {})
    (nil? (:project s)) (send :project {})))

(defmethod step :disconnected [{s :state :as r} _]
  (-> r
      ;; the editor's completion requests still waiting are answered, with the file's words
      (update :fx into (for [req (vals (:pending s)) :when (= :completions (:op req))]
                         [:completed (:token req) nil]))
      ;; no reply comes any more, so no entry may stay running
      (change (fn [s] (-> s
                          ;; nothing is known about the REPLs until the next evaluation
                          (assoc :conn :closed :pending {} :repl {})
                          (update :results (fn [rs] (mapv #(cond-> % (= :running (:status %))
                                                             (assoc :status :unavailable
                                                                    :message "Disconnected before the reply came."))
                                                          rs))))))
      (toast :error "Disconnected from bareedit. Restart it to keep editing.")))

(defmethod step :page-config [r [_ config]]
  (change r assoc :config config))

(defmethod step :focus [r _]
  (refresh r))

(defmethod step :message [r [_ text]]
  (let [msg (p/decode text)]
    (case (p/kind msg)
      :reply (if-let [req (get-in r [:state :pending (:id msg)])]
               (-> r (change update :pending dissoc (:id msg)) (on-reply req msg))
               r)
      :event (cond-> r
               (= :out (:event msg)) (change (fn [s] (-> s
                                                         (update :next-id inc)
                                                         (add-entry {:id (:next-id s) :kind :out :out (:text msg)})
                                                         show-results))))
      r)))

(defn- add-buffer
  "Returns the state `s` with `buffer` given the next id and opened in a new, active tab."
  [s buffer]
  (let [id (:next-buffer s)]
    (-> s
        (update :next-buffer inc)
        (assoc-in [:buffers id] (assoc buffer :id id))
        (update :tabs conj id)
        (assoc :active id))))

(defn- open-path
  "Returns the result `r` with `path` in the active tab.
  A path that is open switches to its tab. Any other opens in a new tab that loads it."
  [{s :state :as r} path]
  (if-let [id (first (buffer-ids-for s path))]
    (change r assoc :active id)
    (-> r
        (change add-buffer {:path path :loading? true})
        (read-paths [path]))))

(defmethod run-command :open-settings [r _] (open-path r :settings))

(defmethod step :open-file [{s :state :as r} [_ path]]
  (if (some #{path} (:paths s))
    (open-path r path)
    r))                                            ; a folder in the tree

(defmethod step :select-tab [r [_ id]]
  (change r assoc :active id))

(defmethod step :close-tab [r [_ id]]
  (close-tab r id))

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

(defn- rename-file
  "Returns the result `r` with the file of the buffer `id` renamed to `to`.
  Its own path renames nothing, and a [[busy?]] file is refused with a toast. A file that
  was never saved is not on disk, so its buffers take the new path at once; any other file
  is renamed on disk first, see the `:rename` reply."
  [{s :state :as r} id to]
  (let [{:keys [path disk-version]} (get-in s [:buffers id])]
    (cond
      (= to path)                r
      (busy? s path)             (busy-toast r path)
      (= :absent disk-version)   (change r rename-buffers path to)
      :else                      (send r :rename {:from path :to to :version disk-version}))))

(defn- new-file
  "Returns the result `r` with a tab for `path`, a file that need not exist yet.
  An open file switches to its tab, and a file in the path list opens. Any other path opens
  an empty buffer that is not on disk, which the first save creates."
  [{s :state :as r} path]
  (if (or (seq (buffer-ids-for s path)) (some #{path} (:paths s)))
    (open-path r path)
    (change r add-buffer {:path path :disk-text "" :disk-version :absent
                          :state (editor/new-state "" (model/language-of path))})))

(defmethod step :answer [{s :state :as r} [_ choice]]
  (let [{:keys [kind buffer disk path] :as question} (first (:questions s))]
    (cond
      (nil? question) r
      ;; a path with a problem keeps its question open
      (and (#{:create :rename} choice) (question-problem s question)) r
      :else
      (let [r (change r update :questions #(vec (rest %)))]
        (case kind
          :new-file    (case choice
                         :create (new-file r (model/normalize-path path))
                         :cancel r)
          :rename      (case choice
                         :rename (rename-file r buffer (model/normalize-path path))
                         :cancel r)
          :close       (case choice
                         :save    (save-buffer r buffer [:close-tab buffer])
                         :discard (change r close-buffer buffer)
                         :cancel  r)
          :disk-change (change r update-in [:buffers buffer]
                               (fn [b] (case choice
                                         :keep (assoc b :disk-text (:text disk) :disk-version (:version disk))
                                         :take (with-disk b disk)))))))))

(defmethod step :question-path [{s :state :as r} [_ text]]
  (if (#{:new-file :rename} (:kind (first (:questions s))))
    (change r assoc-in [:questions 0 :path] text)
    r))

(defmethod step :palette-query [{s :state :as r} [_ text]]
  (if (:palette s) (change r assoc-in [:palette :query] text) r))

(defmethod step :palette-pick [{s :state :as r} [_ id]]
  (let [kind (get-in s [:palette :kind])]
    (if (or (nil? kind) (blocked? s))
      r
      ;; the pick runs with the palette closed; the editor gets the focus first, unless the
      ;; pick opened a palette, as "Go to file" does
      (let [r (case kind
                :files    (open-path (change r assoc :palette nil) id)
                :commands (if-let [c (command-by-name id)]
                            (run-command (change r assoc :palette nil) c)
                            r))]
        (cond-> r
          (nil? (get-in r [:state :palette])) (update :fx #(into [[:focus-editor]] %)))))))

(defmethod step :palette-close [{s :state :as r} _]
  (if (:palette s) (close-palette r) r))

(defmethod step :interrupt [r [_ id]]
  (interrupt r id))

(defmethod step :unfold [r [_ id]]
  (change r update :unfolded #(if (% id) (disj % id) (conj % id))))

(defmethod step :jump-to [{s :state :as r} [_ id]]
  (let [{:keys [path line]} (some #(when (= id (:id %)) %) (:results s))
        open-id             (first (buffer-ids-for s path))]
    (cond
      (nil? path) r
      open-id     (cond-> (change r assoc :active open-id)
                    line (update :fx conj [:goto line]))
      :else       (step r [:open-file path]))))

(defmethod step :complete [{s :state :as r} [_ {:keys [prefix token]}]]
  (let [{:keys [path state]} (active-buffer s)]
    (if (and state (= :clj (p/kind-for path)) (connected? s))
      (send r :completions {:prefix prefix :ns (model/ns-of (editor/text state))} {:token token})
      (update r :fx conj [:completed token nil]))))

;; ── Find ────────────────────────────────────────────────────────────────────

(def find-defaults
  "The find bar when it first opens.
  `:scope` is `:file` to search the active buffer or `:project` to search every file.
  `:query` is the query, see [[bare-edit.search/query?]], and `:replacement` the text
  that replaces a match in the file. `:results` is the search of the project, or `nil`
  before there is one. It holds `:searching`, the id of the newest search, whose reply
  alone counts, and `:found`, the reply to it, `{:matches :more}` or `{:problem message}`.
  It also holds `:selected`, the selected match as `{:path :line}`, which names it in any
  search, and `:folded`, the paths whose matches are hidden. Only the commands set
  `:scope`: `Mod-f` and `Mod-Shift-f` say where to search."
  {:scope :file :query search/blank-query :replacement "" :results nil})

(defn find-query
  "The query of the find bar in the state `s`, whose matches the editor marks, or `nil`.
  It is `nil` when the bar is closed."
  [s]
  (get-in s [:find :query]))

(defn- search-project
  "Returns the result `r` with the project's results dropped and searched for again.
  It is for a new query, whose old results no longer apply, see [[refresh-project]]."
  [r]
  (refresh-project (change r update :find dissoc :results)))

(defn- open-find
  "Returns the result `r` with the find bar open on `scope`, and its query field focused.
  Text selected on one line in the active buffer becomes the query. The project is
  searched again each time, since its files may have changed. A new query or scope
  clears the old matches first."
  [{s :state :as r} scope]
  (let [old      (:find s)
        selected (some-> (active-buffer s) :state editor/selected-text)
        f        (cond-> (assoc (or old find-defaults) :scope scope)
                   selected (assoc-in [:query :text] selected))
        r        (-> r (change assoc :find f) (update :fx conj [:focus-find]))]
    (if (or (not= (:query f) (:query old))
            (not= scope (:scope old))
            (nil? (:results f)))
      (search-project r)
      (refresh-project r))))

(defn- open-at
  "Returns the result `r` with `path` in the active tab and the cursor at `line`.
  A file that still loads keeps the line as its buffer's `:goto`, and the cursor moves
  there once it has loaded, see [[loaded-gotos]]."
  [{s :state :as r} path line]
  (if-let [id (first (buffer-ids-for s path))]
    (if (get-in s [:buffers id :loading?])
      (change r #(-> % (assoc :active id) (assoc-in [:buffers id :goto] line)))
      (-> r (change assoc :active id) (update :fx conj [:goto line])))
    (-> r
        (change add-buffer {:path path :loading? true :goto line})
        (read-paths [path]))))

(defn- match-id
  "The name of `match`, a match of a project search, as `{:path :line}`.
  A search finds a line once, so the name stays the same in a search again."
  [match]
  (select-keys match [:path :line]))

(defn selected-index
  "The index of the selected match among the matches found in `results`, or `nil`.
  `results` is a search of the project, see [[find-defaults]]. It is `nil` when no match
  is selected, or when the last search no longer found it."
  [{:keys [found selected]}]
  (when selected
    (first (keep-indexed (fn [i m] (when (= selected (match-id m)) i)) (:matches found)))))

(defn- select-match
  "Returns the result `r` with the match `i` of the project search selected and shown.
  Its file's matches unfold, and the list scrolls to it."
  [{s :state :as r} i]
  (if-let [{:keys [path] :as m} (get-in s [:find :results :found :matches i])]
    (-> r
        (change update-in [:find :results] #(-> % (assoc :selected (match-id m)) (update :folded disj path)))
        (update :fx conj [:reveal-hit]))
    r))

(defn- open-match
  "Returns the result `r` with the match `i` of the project search selected and opened at its line."
  [{s :state :as r} i]
  (if-let [{:keys [path line]} (get-in s [:find :results :found :matches i])]
    (-> r (select-match i) (open-at path line))
    r))

(defn- index-after
  "The index of the match `delta` places from the selected one in `results`, or `nil`.
  It wraps around at either end. With none selected, the next is the first and the
  previous the last. It is `nil` when there are no matches."
  [results delta]
  (let [n (count (get-in results [:found :matches]))]
    (when (pos? n)
      (mod (+ (or (selected-index results) (if (pos? delta) -1 0)) delta) n))))

(defn- select-step
  "Returns the result `r` with the match `delta` places from the selected one selected."
  [{s :state :as r} delta]
  (if-let [i (index-after (get-in s [:find :results]) delta)] (select-match r i) r))

(defn- find-in-editor
  "Returns the result `r` with the find command `id` to run on the active buffer, if any.
  The effect carries the find bar's query and replacement, so the command acts on those of
  this state."
  [{s :state :as r} id]
  (let [{:keys [query replacement]} (:find s)]
    (cond-> r
      (active-buffer s) (update :fx conj [:find-in-editor id query replacement]))))

(defn- find-step
  "Returns the result `r` with the find bar moved `delta`, 1 or -1, matches on.
  In the file it selects the next or the previous match. In the project it opens the
  next or the previous match of the search, wrapping around at either end. A closed bar
  opens on the file."
  [{s :state :as r} delta]
  (let [f (:find s)
        i (index-after (:results f) delta)]
    (cond
      (nil? f)               (open-find r :file)
      (= :file (:scope f))   (find-in-editor r (if (pos? delta) :next-match :previous-match))
      i                      (open-match r i)
      :else                  r)))

(def find-keys
  "The keys of the find bar's query field that the find bar acts on, not the field."
  #{"Enter" "ArrowUp" "ArrowDown"})

(defn- find-key
  "Returns the result `r` after the key `k` of [[find-keys]] in the query field, `shift?` held.
  Enter goes to the next match and Shift-Enter to the previous. In the project, Enter opens
  the selected match, or the first, and the arrow keys select one without opening it. In
  the file the arrow keys select the next or the previous match, as Enter does."
  [{s :state :as r} k shift?]
  (let [{:keys [scope results]} (:find s)
        project?                (= :project scope)]
    (case k
      "Enter"     (cond shift?   (find-step r -1)
                        project? (if (index-after results 1) (open-match r (or (selected-index results) 0)) r)
                        :else    (find-step r 1))
      "ArrowDown" (if project? (select-step r 1) (find-step r 1))
      "ArrowUp"   (if project? (select-step r -1) (find-step r -1))
      r)))

(defmethod run-command :find            [r _] (open-find r :file))
(defmethod run-command :find-in-project [r _] (open-find r :project))
(defmethod run-command :find-next       [r _] (find-step r 1))
(defmethod run-command :find-previous   [r _] (find-step r -1))

(defmethod step :find-set [{s :state :as r} [_ k v]]
  (cond-> r
    (and (:find s) (contains? search/blank-query k)) (-> (change assoc-in [:find :query k] v)
                                                        search-project)))

(defmethod step :find-replacement [{s :state :as r} [_ text]]
  (cond-> r
    (:find s) (change assoc-in [:find :replacement] text)))

(defmethod step :find-open [r [_ i]]
  (open-match r i))

(defmethod step :find-select [r [_ delta]]
  (select-step r delta))

(defmethod step :find-key [r [_ k shift?]]
  (find-key r k shift?))

(defmethod step :find-fold [{s :state :as r} [_ path]]
  (cond-> r
    (get-in s [:find :results]) (change update-in [:find :results :folded]
                                        #(if (contains? % path) (disj % path) (conj (or % #{}) path)))))

(defmethod step :find-edit [{s :state :as r} [_ id]]
  (cond-> r
    (= :file (get-in s [:find :scope])) (find-in-editor id)))

;; ── After each event ────────────────────────────────────────────────────────

(defn- disk-moved?
  "Whether the project on disk changed between the states `old` and `new`, as far as known.
  It changed when the path list changed, or when a file that was read before has another
  version on disk. A file read for the first time is no change."
  [old new]
  (let [versions (fn [s] (into {} (for [b (vals (:buffers s)) :when (:disk-version b)]
                                    [(:path b) (:disk-version b)])))
        before   (versions old)]
    (or (not= (:paths old) (:paths new))
        (some (fn [[path v]] (and (contains? before path) (not= v (before path))))
              (versions new)))))

(defn advance
  "Returns the result `r` after `event`: the [[step]], and what follows from the change.
  When the project on disk changed and the find bar shows matches of the project, the
  project is searched again, so the matches follow the disk, see [[disk-moved?]]."
  [{old :state :as r} event]
  (let [{s :state :as r} (step r event)]
    (cond-> r
      (and (get-in s [:find :results :found]) (disk-moved? old s)) refresh-project)))
