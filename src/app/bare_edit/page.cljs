(ns bare-edit.page
  "The page, as hiccup, as a pure function of the state and a `dispatch` function.
  Every handler on the page does one thing: it calls `dispatch` with an event vector, see
  [[bare-edit.steps/step]]. The page reads nothing else and changes nothing, so a test can
  render it from a state value and check which event each control sends."
  (:require [bare-edit.model :as model]
            [bare-edit.steps :as steps]))

(defn- button
  "An `x-button` of `variant` showing `label` that calls `on-click`."
  [variant label on-click]
  [:x-button {:variant variant :on-click on-click} label])

(defn question-view
  "The dialog for the first open question in the state `s`, or `nil` when there is none.
  Each button calls `dispatch` with `[:answer choice]`; see [[bare-edit.steps/ask]]."
  [s dispatch]
  (let [{:keys [kind buffer path]} (first (:questions s))
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
      (let [problem (model/path-problem path)]
        [:x-modal {:open true :label "New file" :size "sm"
                   :on-x-modal-dismiss (answer :cancel)}
         [:div {:slot "header"} "New file"]
         [:x-form-field {:label "Path in the project" :value path :error (or problem "")
                         :on-x-form-field-input (fn [^js e] (dispatch [:new-file-path (.. e -detail -value)]))
                         :on-keydown (fn [^js e]
                                       (when (= "Enter" (.-key e))
                                         (.preventDefault e)
                                         (dispatch [:answer :create])))}]
         [:div {:slot "footer" :class "actions"}
          (button "secondary" "Cancel" (answer :cancel))
          [:x-button {:variant "primary" :disabled (boolean problem) :on-click (answer :create)} "Create"]]])

      nil)))

(def palette-size
  "How many files the file palette shows at most."
  50)

(defn palette-items
  "The items of the open palette in the state `s`, as data for `x-command-palette`.
  The file palette holds the best paths for the query, in rank order, which the palette
  shows as given. The command palette holds every command it shows, and filters them itself."
  [{:keys [palette paths]}]
  (case (:kind palette)
    :files    (for [p (model/rank-paths paths (:query palette) palette-size)]
                {:id p :label p})
    :commands (for [{:keys [id label palette?]} steps/commands
                    :when (not (false? palette?))]
                {:id (name id) :label label})
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

(defn tab-label
  "The tab text for the buffer `b`: its file name, marked when deleted or unsaved."
  [b]
  (str (model/file-name (:path b))
       (cond (:deleted? b)       " (deleted)"
             (steps/unsaved? b)  " ●")))

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

(defn page
  "The page, as hiccup, for the state `s`, whose controls call `dispatch` with events.
  It reads the `:paths`, `:expanded`, `:tabs`, `:buffers`, `:active`, `:conn`,
  `:questions` and `:palette` of `s`."
  [{:keys [tabs buffers active paths expanded conn] :as s} dispatch]
  (let [active-b (get buffers active)]
    [:x-theme {:preset "default" :class "app"}
     [:x-split-pane {:position "22" :class "panes"}
      [:div {:slot "start" :class "files"}
       [:x-tree {:label "Files" :selection "leaf" :guides true
                 :items (tree-json paths)
                 :expanded (js/JSON.stringify (clj->js expanded))
                 :selected (js/JSON.stringify (clj->js (if active-b [(:path active-b)] [])))
                 :on-x-tree-toggle (fn [^js e] (dispatch [:expand (vec (.. e -detail -nextExpanded))]))
                 :on-x-tree-select (fn [^js e] (dispatch [:open-file (.. e -detail -id)]))}]]
      [:div {:slot "end" :class "main"}
       (if (seq tabs)
         [:x-tabs {:value (str active) :label "Open files"
                   :on-value-change-request (fn [^js e]
                                              (.preventDefault e)
                                              (dispatch [:select-tab (js/parseInt (.. e -detail -value))]))}
          (for [id tabs]
            [:x-tab {:value (str id) :variant "underline" :size "sm"} (tab-label (get buffers id))])]
         [:div {:class "empty"} (if (= conn :closed) "Disconnected. Restart bare-edit." "Open a file from the tree.")])
       ;; hidden while the active file loads, so nothing typed lands in the previous file's text
       [:div {:id "editor" :data-keep true :style (if (:state active-b) "" "display:none")}]]]
     ;; render matches children by position, so each overlay has a fixed slot and one
     ;; appearing never moves another; the toaster keeps its toasts (:data-keep)
     [:x-toaster {:id "toaster" :data-keep true}]
     [:div {:class "slot"} (question-view s dispatch)]
     [:div {:class "slot"} (palette-view s dispatch)]]))
