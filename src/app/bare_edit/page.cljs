(ns bare-edit.page
  "The page, as hiccup, as a pure function of the state and a `dispatch` function.
  Every handler on the page does one thing: it calls `dispatch` with an event vector, see
  [[bare-edit.steps/step]]. The page reads nothing else and changes nothing, so a test can
  render it from a state value and check which event each control sends."
  (:require [bare-edit.editor :as editor]
            [bare-edit.keys :as keymap]
            [bare-edit.model :as model]
            [bare-edit.protocol :as p]
            [bare-edit.steps :as steps]
            [clojure.string :as str]))

(defn- button
  "An `x-button` of `variant` showing `label` that calls `on-click`."
  [variant label on-click]
  [:x-button {:variant variant :on-click on-click} label])

(defn- path-dialog
  "The dialog that asks for a path, titled `title`, whose button `label` answers `choice`.
  It shows the path of `question` and its `problem`, if any, and disables the button while
  there is one. Typing calls `dispatch` with `[:question-path text]`, and Enter answers
  like the button."
  [title label choice {:keys [path]} problem dispatch]
  (let [answer (fn [choice] (fn [_] (dispatch [:answer choice])))]
    [:x-modal {:open true :label title :size "sm" :on-x-modal-dismiss (answer :cancel)}
     [:div {:slot "header"} title]
     [:x-form-field {:label "Path in the project" :value path :error (or problem "")
                     :on-x-form-field-input (fn [^js e] (dispatch [:question-path (.. e -detail -value)]))
                     :on-keydown (fn [^js e]
                                   (when (= "Enter" (.-key e))
                                     (.preventDefault e)
                                     (dispatch [:answer choice])))}]
     [:div {:slot "footer" :class "actions"}
      (button "secondary" "Cancel" (answer :cancel))
      [:x-button {:variant "primary" :disabled (boolean problem) :on-click (answer choice)} label]]]))

(defn question-view
  "The dialog for the first open question in the state `s`, or `nil` when there is none.
  Each button calls `dispatch` with `[:answer choice]`; see [[bare-edit.steps/ask]]."
  [s dispatch]
  (let [{:keys [kind buffer] :as question} (first (:questions s))
        file   (model/file-name (get-in s [:buffers buffer :path] ""))
        answer (fn [choice] (fn [_] (dispatch [:answer choice])))]
    (case kind
      :disk-change
      [:x-modal {:open true :label "Changed on disk" :size "sm"
                 :on-x-modal-dismiss (fn [^js e] (.preventDefault e))}
       [:div {:slot "header"} (str file " changed on disk")]
       [:p "It has unsaved changes here too. Keep your version or take the one on disk?"]
       [:div {:slot "footer" :class "actions"}
        (button "secondary" "Take disk" (answer :take))
        (button "primary" "Keep mine" (answer :keep))]]

      :close
      [:x-modal {:open true :label "Unsaved changes" :size "sm"
                 :on-x-modal-dismiss (answer :cancel)}
       [:div {:slot "header"} (str "Save " file "?")]
       [:p "It has unsaved changes."]
       [:div {:slot "footer" :class "actions"}
        (button "secondary" "Cancel" (answer :cancel))
        (button "danger" "Discard" (answer :discard))
        (button "primary" "Save" (answer :save))]]

      :new-file
      (path-dialog "New file" "Create" :create question (steps/question-problem s question) dispatch)

      :rename
      (path-dialog (str "Rename " file) "Rename" :rename question (steps/question-problem s question) dispatch)

      nil)))

(def palette-size
  "How many files the file palette shows at most."
  50)

(defn palette-items
  "The items of the open palette in the state `s`, as data for `x-command-palette`.
  The file palette holds the best paths for the query, in rank order, which the palette
  shows as given. The command palette holds every command it shows, and filters them itself.
  Its commands are those with a group, group by group in the order of
  [[bare-edit.steps/command-groups]], so the palette shows each group's header once; within
  a group they keep the order of [[bare-edit.steps/commands]]. A command with keys shows
  them, see [[bare-edit.keys/shortcut]]."
  [{:keys [palette paths]}]
  (case (:kind palette)
    :files    (for [p (model/rank-paths paths (:query palette) palette-size)]
                {:id p :label p})
    :commands (for [{:keys [id label group] spec :keys} (->> steps/commands
                                                             (filter :group)
                                                             (sort-by (comp steps/group-order :group)))]
                (cond-> {:id (name id) :label label :group (steps/group-labels group)}
                  spec (assoc :shortcut (keymap/shortcut spec))))
    nil))

(def palette-texts
  "The label and placeholder of each kind of palette."
  {:files    {:label "Go to file" :placeholder "File name or namespace"}
   :commands {:label "Commands"   :placeholder "Command"}})

(defn palette-view
  "The open palette for the state `s`, whose choices call `dispatch`, or `nil` when none is open.
  It is made new on each opening, and when it changes kind, so it starts empty, with its
  input focused. The file
  palette shows its items as given, since the editor ranks them. The palette is a view of
  the state: its own pick and close are refused and sent to `dispatch` instead, so it opens
  and closes only with the state. Escape reaches the page's key listener, which closes it,
  so the palette's own Escape is off."
  [{:keys [palette] :as s} dispatch]
  (when palette
    [:x-command-palette
     {:key (:kind palette) :open true :close-on-escape "false"
      :filter (if (= :files (:kind palette)) "none" "default")
      :label (get-in palette-texts [(:kind palette) :label])
      :placeholder (get-in palette-texts [(:kind palette) :placeholder])
      :prop/items (palette-items s)
      :on-x-command-palette-query-change (fn [^js e] (dispatch [:palette-query (.. e -detail -query)]))
      :on-x-command-palette-select-request (fn [^js e]
                                             (.preventDefault e)
                                             (dispatch [:palette-pick (.. e -detail -item -id)]))
      :on-x-command-palette-close-request (fn [^js e]
                                            (.preventDefault e)
                                            (dispatch [:palette-close]))}]))

(def fold-lines
  "How many lines of a value or of output a folded results entry shows."
  8)

(defn folded
  "The text `text` cut to its first [[fold-lines]] lines, unless `expanded?`.
  Returns `[shown cut?]`, where `cut?` says whether lines were left out."
  [text expanded?]
  (let [lines (str/split-lines (str text))]
    (if (or expanded? (<= (count lines) fold-lines))
      [(str text) false]
      [(str/join "\n" (take fold-lines lines)) true])))

(defn- code-view
  "The Clojure text `text`, highlighted, in a `pre` of `class`."
  [class text]
  [:pre {:class class}
   (for [[piece cls] (editor/highlighted text)]
     (if cls [:span {:class cls} piece] piece))])

(def status-marks
  "The mark in a results entry's header for each status."
  {:running "…" :ok "✓" :error "✗" :interrupted "■" :unavailable "!" :no-runtime "!" :conflict "!"})

(defn entry-title
  "The heading of the results entry `entry`, after its status mark."
  [{:keys [kind path line form sym]}]
  (case kind
    :eval (str (model/file-name path) ":" line "  " (model/form-head form 50))
    :doc  (str "Docs: " sym)
    :out  "Output"))

(defn- entry-body
  "The body of the results entry `entry`, whose controls call `dispatch`.
  Output comes first, then the value, the docs or the message. Long text is folded, see
  [[folded]], unless `expanded?`, with a button that shows it all."
  [{:keys [id kind status out err value message info sym]} expanded? dispatch]
  (let [[out out-cut?]     (folded out expanded?)
        [value value-cut?] (folded value expanded?)
        cut?               (or out-cut? value-cut?)]
    [:div {:class "entry-body"}
     (when (seq out) [:pre {:class "out"} out])
     (when (seq err) [:pre {:class "err"} err])
     (case status
       :running [:x-button {:variant "secondary" :size "sm" :on-click (fn [_] (dispatch [:interrupt id]))}
                 "Interrupt"]
       :ok      (case kind
                  :eval (code-view "value" value)
                  :doc  (if (seq info)
                          [:div {:class "doc"}
                           [:code (str (:ns info) (when (:ns info) "/") (:name info))]
                           (when-let [args (:arglists-str info)] (code-view "value" args))
                           (when-let [doc (:doc info)] [:pre {:class "out"} doc])]
                          [:div {:class "message"} (str "No docs for " sym ".")])
                  nil)
       nil      nil
       [:div {:class "message"} message])
     (when (or cut? expanded?)
       [:x-button {:variant "ghost" :size "sm" :on-click (fn [_] (dispatch [:unfold id]))}
        (if expanded? "Show less" "Show all")])]))

(defn entry-view
  "The results entry `entry`, shown in full when `expanded?`, whose controls call `dispatch`.
  Its header shows the status mark and the heading, and a click on it calls `dispatch` with
  `[:jump-to id]` when the entry has code to go to."
  [{:keys [id status path] :as entry} expanded? dispatch]
  [:div {:class "entry"}
   [:div (if path
           {:class "entry-head jumps" :on-click (fn [_] (dispatch [:jump-to id]))}
           {:class "entry-head"})
    [:span {:class (str "mark " (some-> status name))} (get status-marks status "")]
    [:span {:class "title"} (entry-title entry)]]
   (entry-body entry expanded? dispatch)])

(defn results-view
  "The results column for the state `s`, whose controls call `dispatch`: a bar and the entries."
  [{:keys [results unfolded]} dispatch]
  [:div {:class "results"}
   [:div {:class "bar results-bar"}
    [:span "Results"]
    [:x-button {:variant "ghost" :size "sm"
                :on-click (fn [_] (dispatch [:command (steps/command-by-name "clear-results")]))}
     "Clear"]]
   (if (seq results)
     [:div {:class "entries"} (for [e results] (entry-view e (contains? unfolded (:id e)) dispatch))]
     [:div {:class "empty"} "Evaluate a form to see its result here."])])

(defn tab-label
  "The tab text for the buffer `b`: its file name, marked when deleted or unsaved."
  [b]
  (str (model/file-name (:path b))
       (cond (:deleted? b)       " (deleted)"
             (steps/unsaved? b)  " ●")))

(defn tab-view
  "The tab of the buffer `b`, with its [[tab-label]] and a button that closes it.
  A click on the button calls `dispatch` with `[:close-tab id]` and goes no further, so
  the tab is not selected first."
  [{:keys [id path] :as b} dispatch]
  [:x-tab {:value (str id) :variant "underline" :size "sm"}
   [:span {:class "tab-name"} (tab-label b)]
   [:span {:class "tab-close" :role "button" :title "Close tab"
           :aria-label (str "Close " (model/file-name path))
           :on-click (fn [^js e] (.stopPropagation e) (dispatch [:close-tab id]))}
    "×"]])

(def brand
  "The editor's name in the style of the BareDOM logo: `<BareEdit>`, with blue brackets."
  [:span {:class "brand"} [:span {:class "bracket"} "<"] "BareEdit" [:span {:class "bracket"} ">"]])

(def kind-names
  "The name of each session kind, as the status bar shows it."
  {:clj "Clojure" :cljs "ClojureScript"})

(def conn-states
  "What the status bar shows for each state of the connection: its words and its tone.
  The tone, `ok`, `busy`, `bad` or `idle`, is the class that colours the dot."
  {:connecting {:words "connecting"   :tone "busy"}
   :open       {:words "connected"    :tone "ok"}
   :closed     {:words "disconnected" :tone "bad"}})

(def repl-states
  "What the status bar shows for the last status heard from a REPL: its words and its tone.
  `nil` is a REPL that has not been used. An evaluation that failed or was interrupted
  still had a REPL that answered, so it is ready."
  {nil          {:words "not used yet"  :tone "idle"}
   :running     {:words "busy"          :tone "busy"}
   :ok          {:words "ready"         :tone "ok"}
   :error       {:words "ready"         :tone "ok"}
   :interrupted {:words "ready"         :tone "ok"}
   :no-runtime  {:words "no JS runtime" :tone "bad"}
   :unavailable {:words "unavailable"   :tone "bad"}})

(defn status-bar
  "The bar under the panes for the state `s`, whose palette button calls `dispatch`.
  On the left it shows the project's name, the connection and the last thing heard from
  the REPL of each session kind, see [[repl-states]], with its message as a tooltip. On the
  right it shows the active file's session kind, the cursor's line and column, and a button
  that opens the command palette."
  [{:keys [project conn repl buffers active]} dispatch]
  (let [{:keys [path state]} (get buffers active)
        [line col]           (some-> state editor/cursor-position)]
    [:div {:class "status-bar"}
     [:span {:class "project"} (or project "")]
     (let [{:keys [words tone]} (conn-states conn)]
       [:span {:class (str "dot " tone)} words])
     (for [kind [:clj :cljs]
           :let [{:keys [status message]} (get repl kind)
                 {:keys [words tone]}     (repl-states status)]]
       [:span {:class (str "dot " tone) :title (or message "")}
        (str (kind-names kind) ": " words)])
     [:span {:class "fill"}]
     [:span {:class "kind"} (or (kind-names (p/kind-for path)) "")]
     [:span {:class "cursor"} (if line (str "Ln " line ", Col " col) "")]
     [:x-button {:variant "ghost" :size "sm" :title "Show all commands (Mod-Shift-p or F1)"
                 :on-click (fn [_] (dispatch [:command (steps/command-by-name "command-palette")]))}
      "Commands"]]))

(def tree-json
  "Returns the `x-tree` items for the path list `paths`, as JSON.
  The items are derived from the paths, so they are not stored in the state. The last
  result is kept, since the list rarely changes between renders."
  (let [last-result (atom [nil "[]"])]
    (fn [paths]
      (let [[k v] @last-result]
        (if (= k paths)
          v
          (let [v (js/JSON.stringify (clj->js (model/tree-items paths)))]
            (reset! last-result [paths v])
            v))))))

(defn font-style
  "The inline style that gives an element the fonts of the look and feel `config`.
  It sets `--x-font-family-mono` and `--x-font-size-base`, in pixels, only for the ones
  `config` names, and is empty otherwise. It goes on the editor and the results column,
  not on the `x-theme`, because `--x-font-size-base` sizes the whole UI."
  [{:keys [font-mono font-size]}]
  (str (when font-mono (str "--x-font-family-mono:" font-mono ";"))
       (when font-size (str "--x-font-size-base:" font-size "px;"))))

(defn syntax-style
  "The CSS that sets the theme's syntax colours to the look and feel's `syntax` overrides.
  `syntax` holds a map of token to colour for each mode, `:light` and `:dark`. Each mode's
  colours apply only in that mode, so a colour set for one never shows in the other, and a
  token without an override keeps the preset's colour. The rule is on the app, the
  `x-theme` itself, so it wins over the preset's own values."
  [{:keys [light dark]}]
  (let [rule (fn [mode colours]
               (when (seq colours)
                 (str "@media (prefers-color-scheme: " mode "){.app{"
                      (apply str (for [[token colour] (sort colours)]
                                   (str "--x-color-syntax-" (name token) ":" colour ";")))
                      "}}")))]
    (str (rule "light" light) (rule "dark" dark))))

(defn page
  "The page, as hiccup, for the state `s`, whose controls call `dispatch` with events.
  It reads the `:paths`, `:expanded`, `:tabs`, `:buffers`, `:active`, `:conn`,
  `:questions`, `:palette`, `:results`, `:results-open?` and `:config` of `s`, and what
  [[status-bar]] reads. Until the
  look and feel arrives, the `x-theme` uses its own preset. The results column is always
  on the page, so the editor never moves; when closed, its pane takes no room."
  [{:keys [tabs buffers active paths expanded conn results-open? config] :as s} dispatch]
  (let [active-b (get buffers active)
        fonts    (font-style config)]
    [:x-theme {:preset (:preset config) :class "app"}
     [:x-split-pane {:position "22" :class "panes"}
      [:div {:slot "start" :class "files"}
       [:div {:class "bar files-bar"} brand]
       [:div {:class "tree"}
        [:x-tree {:label "Files" :selection "leaf" :guides true
                  :items (tree-json paths)
                  :expanded (js/JSON.stringify (clj->js expanded))
                  :selected (js/JSON.stringify (clj->js (if active-b [(:path active-b)] [])))
                  :on-x-tree-toggle (fn [^js e] (dispatch [:expand (vec (.. e -detail -nextExpanded))]))
                  :on-x-tree-select (fn [^js e] (dispatch [:open-file (.. e -detail -id)]))}]]]
      [:x-split-pane {:slot "end" :class (if results-open? "inner" "inner closed")
                      :position (if results-open? "62" "100") :disabled (not results-open?)}
      [:div {:slot "start" :class "main"}
       ;; the tab bar shows even with no tabs, so its line meets the results bar's
       [:x-tabs {:class "bar" :value (str active) :label "Open files"
                 :on-value-change-request (fn [^js e]
                                            (.preventDefault e)
                                            (dispatch [:select-tab (js/parseInt (.. e -detail -value))]))}
        (for [id tabs]
          (tab-view (get buffers id) dispatch))]
       ;; hidden while the active file loads, so nothing typed lands in the previous file's text
       [:div {:id "editor" :data-keep true :style (str fonts (when-not (:state active-b) "display:none"))}]
       ;; render matches children by position, so the message comes after the editor: one
       ;; that came before would move the editor's place, and the editor would be rebuilt
       (when (empty? tabs)
         [:div {:class "empty"} (if (= conn :closed) "Disconnected. Restart bareedit." "Open a file from the tree.")])]
      [:div {:slot "end" :class "results-pane" :style fonts} (when results-open? (results-view s dispatch))]]]
     (status-bar s dispatch)
     ;; render matches children by position, so each overlay has a fixed slot and one
     ;; appearing never moves another; the toaster keeps its toasts (:data-keep)
     [:x-toaster {:id "toaster" :data-keep true}]
     [:div {:class "slot"} (question-view s dispatch)]
     [:div {:class "slot"} (palette-view s dispatch)]
     [:style {:class "syntax"} (syntax-style (:syntax config))]]))
