(ns bare-edit.editor
  "The CodeMirror 6 editor for Clojure.
  There is one view; each buffer holds its own immutable `EditorState`, and the view shows
  the active buffer's. The theme and highlighting use BareDOM's `--x-*` colour tokens, so
  dark mode follows the BareDOM theme."
  (:require ["@codemirror/autocomplete" :refer [autocompletion completeAnyWord completionKeymap
                                                startCompletion]]
            ["@codemirror/commands" :refer [history historyKeymap standardKeymap toggleComment]]
            ["@codemirror/language" :refer [bracketMatching HighlightStyle syntaxHighlighting
                                            syntaxTree]]
            ["@codemirror/search" :refer [openSearchPanel search searchKeymap]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView keymap]]
            ["@lezer/highlight" :refer [tags]]
            [nextjournal.clojure-mode :as cm-clj]
            [nextjournal.clojure-mode.commands :as commands]
            [nextjournal.clojure-mode.extensions.close-brackets :as close-brackets]
            [nextjournal.clojure-mode.extensions.formatting :as formatting]
            [nextjournal.clojure-mode.extensions.selection-history :as sel-history]))

(def theme
  "The editor's look, from BareDOM's colour and font tokens.
  It covers the text and every CodeMirror surface the editor shows: the completion popup,
  the search panel with its fields and buttons, and the search matches, so all of them
  follow the BareDOM theme in light and dark mode."
  (.theme EditorView
          (clj->js {"&"                          {:height "100%" :color "var(--x-color-text)"
                                                  :backgroundColor "var(--x-color-bg)"}
                    ".cm-scroller"               {:fontFamily "var(--x-font-family-mono, monospace)"
                                                  :fontSize "var(--x-font-size-base, 14px)"
                                                  :lineHeight "1.5"}
                    ".cm-content"                {:caretColor "var(--x-color-primary)"}
                    ".cm-cursor"                 {:borderLeftColor "var(--x-color-primary)"}
                    "&.cm-focused"               {:outline "none"}
                    ".cm-gutters"                {:backgroundColor "var(--x-color-bg)"
                                                  :color "var(--x-color-text-muted)" :border "none"}
                    ".cm-activeLine"             {:backgroundColor "var(--x-color-surface-hover)"}
                    ".cm-selectionBackground, &.cm-focused .cm-selectionBackground"
                    {:backgroundColor "color-mix(in srgb, var(--x-color-primary) 25%, transparent)"}
                    ".cm-matchingBracket"        {:color "var(--x-color-primary)" :fontWeight "bold"
                                                  :backgroundColor "transparent"}
                    ".cm-nonmatchingBracket"     {:color "var(--x-color-danger)"}
                    ".cm-tooltip"                {:backgroundColor "var(--x-color-bg)"
                                                  :color "var(--x-color-text)"
                                                  :border "1px solid var(--x-color-border)"
                                                  :borderRadius "var(--x-radius-md, 6px)"}
                    ".cm-tooltip-autocomplete > ul > li[aria-selected]"
                    {:backgroundColor "color-mix(in srgb, var(--x-color-primary) 30%, transparent)"
                     :color "var(--x-color-text)"}
                    ".cm-completionMatchedText"  {:textDecoration "none" :fontWeight "bold"
                                                  :color "var(--x-color-primary)"}
                    ".cm-panels"                 {:backgroundColor "var(--x-color-bg)"
                                                  :color "var(--x-color-text)"}
                    ".cm-panels-bottom"          {:borderTop "1px solid var(--x-color-border)"}
                    ".cm-textfield"              {:backgroundColor "var(--x-color-surface)"
                                                  :color "var(--x-color-text)"
                                                  :border "1px solid var(--x-color-border)"
                                                  :borderRadius "var(--x-radius-sm, 4px)"}
                    ".cm-button"                 {:backgroundImage "none"
                                                  :backgroundColor "var(--x-color-surface)"
                                                  :color "var(--x-color-text)"
                                                  :border "1px solid var(--x-color-border)"
                                                  :borderRadius "var(--x-radius-sm, 4px)"}
                    ".cm-searchMatch"            {:backgroundColor "color-mix(in srgb, var(--x-color-warning) 30%, transparent)"}
                    ".cm-searchMatch.cm-searchMatch-selected"
                    {:backgroundColor "color-mix(in srgb, var(--x-color-warning) 60%, transparent)"}})))

(def highlighting
  "Syntax colours from BareDOM's colour tokens."
  (syntaxHighlighting
   (.define HighlightStyle
            (clj->js [{:tag (.-keyword tags) :color "var(--x-color-primary)"}
                      {:tag (.-atom tags) :color "var(--x-color-primary)"}
                      {:tag (.-string tags) :color "var(--x-color-success)"}
                      {:tag (.-regexp tags) :color "var(--x-color-success)"}
                      {:tag (.-number tags) :color "var(--x-color-warning)"}
                      {:tag (.-bool tags) :color "var(--x-color-warning)"}
                      {:tag (.-null tags) :color "var(--x-color-warning)"}
                      {:tag (.-comment tags) :color "var(--x-color-text-muted)" :fontStyle "italic"}
                      {:tag (.-lineComment tags) :color "var(--x-color-text-muted)" :fontStyle "italic"}
                      {:tag (.-definitionKeyword tags) :color "var(--x-color-secondary)"}
                      {:tag (.-macroName tags) :color "var(--x-color-secondary)"}
                      {:tag (.-meta tags) :color "var(--x-color-text-muted)"}]))))

(def ^:private command-keys
  "Keys that CodeMirror's keymaps bind but the editor's commands own.
  On macOS `Ctrl-k` deletes to the line end, but it kills to the end of the form here.
  `Ctrl-Space` and `Mod-f` are the commands that show completions and find."
  #{"Ctrl-k" "Ctrl-Space" "Mod-f"})

(def ^:private left-out
  "Keys of CodeMirror's search keymap for features the editor does not have."
  #{"Mod-Shift-l" "Mod-Alt-g" "Mod-d"})

(defn- without-commands
  "The bindings in the keymap `km` whose keys are not in [[command-keys]] or [[left-out]]."
  [km]
  (let [drop? (some-fn command-keys left-out)]
    (.filter km (fn [^js b] (not (or (drop? (.-key b)) (drop? (.-mac b))))))))

(def ^:private text-bindings
  "The bindings of the text-editing layer, as a JS array, in the order they are tried.
  The keys of the completion popup and the search panel come first: they act only while
  the popup or the panel is open, and otherwise let the key through. Then come Enter and
  Tab, which indent through clojure-mode, and CodeMirror's standard keys and undo history."
  (.concat (without-commands completionKeymap)
           (without-commands searchKeymap)
           #js [#js {:key "Enter" :run commands/enter-and-indent}
                #js {:key "Tab" :run commands/indent}]
           (without-commands standardKeymap)
           historyKeymap))

(def passes-through
  "Keys the text-editing layer binds that also reach the page when they do nothing here.
  Escape closes the completion popup or the search panel only while one is open; otherwise
  it reaches the page, which closes the palette."
  #{"Escape"})

(defn text-layer-keys
  "Every key the text-editing layer binds, in CodeMirror's key syntax, on macOS when `mac?`.
  The editor's commands use none of them, except those in [[passes-through]], so each key
  has one source."
  [mac?]
  (mapcat (fn [^js b]
            (when-let [k (if mac? (or (.-mac b) (.-key b)) (.-key b))]
              ;; a binding with a `shift` command also binds the key with Shift
              (cond-> [k] (.-shift b) (conj (str "Shift-" k)))))
          (array-seq text-bindings)))

(def text-layer
  "The keys that edit text inside the editor, see [[text-bindings]].
  clojure-mode's balanced-bracket keys come with its close-brackets extension. Editor
  commands, such as save or slurp, are matched by the page instead."
  (.of keymap text-bindings))

(def ^:private comment-tokens
  "Tells CodeMirror's toggle comment that a Clojure line comment starts with `;;`."
  (.of (.-languageData EditorState) (fn [] #js [#js {:commentTokens #js {:line ";;"}}])))

(def extensions
  "The extensions every editor state gets.
  These are the undo history, [[text-layer]], clojure-mode's syntax, close brackets and
  selection history, CodeMirror's own bracket matching, search, word completion,
  [[highlighting]] and [[theme]]. CodeMirror's bracket matching replaces clojure-mode's,
  which crashes on current CodeMirror versions. Nothing reformats while typing; a
  structural command tidies the lines it changed, see [[edit]]."
  #js [(history) text-layer (cm-clj/syntax) comment-tokens (close-brackets/extension)
       (sel-history/extension) (bracketMatching) (search)
       (autocompletion #js {:override #js [completeAnyWord] :defaultKeymap false})
       highlighting theme])

(defn new-state
  "A new `EditorState` holding `text`, with an empty undo history."
  [text]
  (.create EditorState #js {:doc text :extensions extensions}))

(defn text
  "The text of `editor-state`, as a string."
  [^js editor-state]
  (.toString (.-doc editor-state)))

(defn- changed-range
  "The part of the string `old` that differs from the string `new`.
  Returns `{:from :to :insert}`, found by skipping the start and the end the two share."
  [old new]
  (let [n      (min (count old) (count new))
        start  (loop [i 0] (if (and (< i n) (= (.charAt old i) (.charAt new i))) (recur (inc i)) i))
        end    (loop [i 0] (if (and (< i (- n start))
                                    (= (.charAt old (- (count old) i 1)) (.charAt new (- (count new) i 1))))
                             (recur (inc i)) i))]
    {:from start :to (- (count old) end) :insert (subs new start (- (count new) end))}))

(defn replaced
  "Returns `editor-state` with its text replaced by `new-text`, as one undoable change.
  Only the part that differs is replaced, so the cursor stays where the text around it is
  unchanged. Undo goes back to the text from before, so a reload from disk can be undone."
  [^js editor-state new-text]
  (let [old (text editor-state)]
    (if (= old new-text)
      editor-state
      (.-state (.update editor-state #js {:changes (clj->js (changed-range old new-text))})))))

(defn- top-form-range
  "The `[from to]` of the top-level form at `pos` in `state`, or `nil` between forms."
  [^js state pos]
  (some (fn [side]
          (loop [^js node (.resolveInner (syntaxTree state) pos side)]
            (cond (nil? node) nil
                  (nil? (.-parent node)) nil
                  (nil? (.. node -parent -parent)) [(.-from node) (.-to node)]
                  :else (recur (.-parent node)))))
        [-1 1]))

(defn- format-lines
  "The transaction that formats the lines of `state` from position `from` to `to`."
  [^js state from to]
  (let [doc     (.-doc state)
        context (formatting/make-indent-context state)
        changes (reduce (fn [changes n]
                          (let [^js line (.line doc n)]
                            (formatting/format-line state context (.-from line) (.-text line) n changes true)))
                        #js []
                        (range (.-number (.lineAt doc from)) (inc (.-number (.lineAt doc to)))))]
    (.update state #js {:changes changes})))

(defn- reindent
  "The transaction that reindents the selection, or the form at the cursor, in `state`.
  Without a selection it reindents the top-level form at the cursor, and returns `nil`
  when the cursor is between forms."
  [^js state]
  (let [^js main (.. state -selection -main)]
    (if (.-empty main)
      (when-let [[from to] (top-form-range state (.-head main))]
        (format-lines state from to))
      (format-lines state (.-from main) (.-to main)))))

(def edits
  "The editor commands that change the text or the selection, by command id.
  Each is a function from an `EditorState` to a `Transaction`, or `nil`. A transaction may
  change nothing, when the command does not apply at the cursor. Each is pure except
  `:kill`, which also copies the killed text to the clipboard."
  {:slurp-forward    (commands/slurp 1)
   :slurp-backward   (commands/slurp -1)
   :barf-forward     (commands/barf 1)
   :barf-backward    (commands/barf -1)
   :unwrap           commands/unwrap*
   :kill             commands/kill*
   :expand-selection sel-history/selection-grow*
   :shrink-selection sel-history/selection-return*
   :reindent         reindent})

(defn edit
  "The transaction that runs the command `id` of [[edits]] on `state`, or `nil`.
  It is `nil` too when the command changes neither the text nor the selection. The lines
  the command changed are tidied by clojure-mode's formatter, and no others."
  [^js state id]
  (when-let [^js tr (when-let [f (edits id)] (f state))]
    (when-not (and (.. tr -changes -empty)
                   (.eq (.-selection state) (.. tr -state -selection)))
      (formatting/format-transaction tr))))

(def view-commands
  "The editor commands that need the view, by command id.
  These are find and replace, toggle comment and show completions. Each takes the
  `EditorView`."
  {:find             openSearchPanel
   :toggle-comment   toggleComment
   :show-completions startCompletion})

(defn command?
  "Whether `id` is an editor command, in [[edits]] or [[view-commands]]."
  [id]
  (or (contains? edits id) (contains? view-commands id)))

(defn run-command!
  "Runs the editor command `id` on the `EditorView` `view`."
  [^js view id]
  (if-let [f (view-commands id)]
    (f view)
    (some->> (edit (.-state view) id) (.dispatch view))))

(defn mount!
  "Creates and returns the `EditorView` inside the element `parent`.
  After each update, which may hold several transactions, it calls `on-state` with the
  `EditorState` the first one started from and the new one, so the buffer holding the
  first can store the second. The view uses
  `root: document`, because CodeMirror would otherwise put its styles in the shadow root of
  the BareDOM slot that `parent` sits in, where they do not reach it."
  [parent on-state]
  (EditorView. #js {:parent parent
                    :root js/document
                    :state (new-state "")
                    :dispatchTransactions (fn [^js trs ^js view]
                                            (let [^js first-tr (aget trs 0)
                                                  start (.-startState first-tr)]
                                              (.update view trs)
                                              (on-state start (.-state view))))}))
