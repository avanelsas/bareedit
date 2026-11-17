(ns bare-edit.app
  "The browser shell: the state atom, the effects, the editor view and the wiring.
  All state lives in one atom. Every event goes through [[dispatch!]], which asks
  [[bare-edit.steps/step]] for the new state and the effects, stores the state once, so the
  page renders once, and then runs the effects."
  (:require [bare-edit.editor :as editor]
            [bare-edit.keys :as keys]
            [bare-edit.page :as page]
            [bare-edit.protocol :as p]
            [bare-edit.render :as render]
            [bare-edit.steps :as steps]
            [baredom.exports.x-button :as x-button]
            [baredom.exports.x-command-palette :as x-command-palette]
            [baredom.exports.x-form-field :as x-form-field]
            [baredom.exports.x-modal :as x-modal]
            [baredom.exports.x-split-pane :as x-split-pane]
            [baredom.exports.x-tab :as x-tab]
            [baredom.exports.x-tabs :as x-tabs]
            [baredom.exports.x-theme :as x-theme]
            [baredom.exports.x-toast :as x-toast]
            [baredom.exports.x-toaster :as x-toaster]
            [baredom.exports.x-tree :as x-tree]))

(defonce ^{:doc "The app's state, the only place it lives. Only [[dispatch!]] changes it."}
  state
  (atom steps/initial-state))

(defonce ^{:doc "The CodeMirror `EditorView` and the buffer it shows, as `{:view :shown}`.
  `:shown` is the id of the buffer whose state the view was last given. Only
  [[sync-view!]] changes it."}
  editor-view
  (atom {:view nil :shown nil}))

(defonce ^{:doc "The WebSocket to the bare-edit server."}
  socket
  (atom nil))

;; ── Effects ─────────────────────────────────────────────────────────────────

(defmulti run-effect!
  "Carries out the `effect` a step returned, dispatched on its first element.
  There is one method for each kind in [[bare-edit.steps/effect-kinds]]."
  (fn [effect] (first effect)))

(defmethod run-effect! :send [[_ req]]
  (when-let [^js ws @socket]
    (.send ws (p/encode req))))

(defmethod run-effect! :focus-editor [_]
  (some-> ^js (:view @editor-view) .focus))

(defmethod run-effect! :edit [[_ id]]
  (some-> ^js (:view @editor-view) (editor/run-command! id)))

(defmethod run-effect! :toast [[_ type message]]
  (when-let [^js t (js/document.getElementById "toaster")]
    (.toast t #js {:type (name type) :message message :timeoutMs 6000})))

(declare sync-view!)

(defn dispatch!
  "Runs `event` through [[bare-edit.steps/step]].
  The new state is stored once, which renders the page, and the effects run in order.
  Last, the view is given the active buffer's editor state, even when the state did not
  change: an edit no buffer took leaves the view holding a state of its own, and this puts
  the buffer's back. Syncing comes last because it can start the next event, as when
  restoring the scroll position, and that event must not run inside this one. It syncs
  with the state as it is then, not as this event left it, because an effect such as
  `[:edit id]` runs an event of its own on the way."
  [event]
  (let [{s :state fx :fx} (steps/step (steps/result @state) event)]
    (when-not (identical? s @state) (reset! state s))
    (run! run-effect! fx)
    (sync-view! @state)))

;; ── Keys ───────────────────────────────────────────────────────────────────

(def mac?
  "Whether this is macOS or iOS, where `Mod` means Cmd and Alt changes characters."
  (boolean (re-find #"Mac|iPhone|iPad" js/navigator.platform)))

(def key-index
  "The commands by their canonical key, for matching key events."
  (keys/key-index mac? steps/commands))

(defn on-keydown
  "Runs the command bound to the key event `e`, if any, and stops the browser handling it.
  The page listens in the bubble phase, so the editor's own keys go first; an event it
  handled is skipped. Nothing runs while a dialog is open. A command with `:scope :editor`
  runs from its key only while the focus is in the editor, its search panel included, so
  elsewhere the key keeps its usual meaning."
  [^js e]
  (when-not (or (.-defaultPrevented e) (steps/blocked? @state))
    (when-let [c (key-index (keys/event->key mac? {:key (.-key e) :code (.-code e) :alt (.-altKey e)
                                                   :ctrl (.-ctrlKey e) :meta (.-metaKey e)
                                                   :shift (.-shiftKey e)}))]
      (when (or (not= :editor (:scope c))
                (some-> ^js (:view @editor-view) .-dom (.contains js/document.activeElement)))
        (.preventDefault e)
        (dispatch! [:command c])))))

;; ── View ────────────────────────────────────────────────────────────────────

(defn sync-view!
  "Gives the view the `EditorState` of the active buffer in the state `s`, when it differs.
  Switching buffers focuses the editor. A new state for the buffer already shown, such as a
  reload from disk, keeps the scroll position. Restoring it is a CodeMirror transaction,
  so it reaches [[dispatch!]] as an `:editor-state` event of its own."
  [s]
  (let [{^js v :view shown :shown} @editor-view
        id (:active s)
        st (get-in s [:buffers id :state])]
    (when (and v st (not (identical? st (.-state v))))
      (swap! editor-view assoc :shown id)
      (if (= id shown)
        (let [snapshot (.scrollSnapshot v)]
          (.setState v st)
          (.dispatch v #js {:effects snapshot}))
        (do (.setState v st)
            (.focus v))))))

;; ── Start ───────────────────────────────────────────────────────────────────

(defn- register-components!
  "Defines the BareDOM custom elements the page uses."
  []
  (doseq [init [x-theme/init x-split-pane/init x-tree/init x-tabs/init x-tab/init
                x-modal/init x-button/init x-toast/init x-toaster/init
                x-command-palette/init x-form-field/init]]
    (init)))

(defn- connect!
  "Opens the WebSocket to the server and dispatches its events.
  It connects with the startup token from the page URL."
  []
  (let [token (.get (js/URLSearchParams. (.. js/window -location -search)) "token")
        ws (js/WebSocket. (str "ws://" (.. js/window -location -host) "/ws?token=" token))]
    (set! (.-onopen ws) #(dispatch! [:connected]))
    (set! (.-onclose ws) #(dispatch! [:disconnected]))
    (set! (.-onmessage ws) (fn [e] (dispatch! [:message (.-data e)])))
    (reset! socket ws)))

(defn init
  "Starts the app and connects to the server.
  It registers the components, renders the page, mounts the editor and renders again on
  every state change. It listens for keys, for window focus, and for leaving the page,
  which warns while a buffer has unsaved changes. An effect kind without a method is
  reported on the console."
  []
  (doseq [k steps/effect-kinds :when (nil? (get-method run-effect! k))]
    (js/console.error "run-effect! has no method for" (str k)))
  (register-components!)
  (let [app (js/document.getElementById "app")]
    (render/render! app #(page/page @state dispatch!))
    (swap! editor-view assoc :view (editor/mount! (js/document.getElementById "editor")
                                                  (fn [old new] (dispatch! [:editor-state old new]))))
    (add-watch state ::render (fn [_ _ _ s] (render/render! app #(page/page s dispatch!)))))
  (.addEventListener js/window "keydown" on-keydown)
  (.addEventListener js/window "focus" #(dispatch! [:focus]))
  (.addEventListener js/window "beforeunload"
                     (fn [^js e] (when (some steps/unsaved? (vals (:buffers @state)))
                                   (.preventDefault e)
                                   (set! (.-returnValue e) ""))))
  (connect!))
