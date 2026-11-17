(ns bare-edit.editor
  "The CodeMirror 6 editor for Clojure.
  There is one view; each buffer holds its own immutable `EditorState`, and the view shows
  the active buffer's. The theme and highlighting use BareDOM's `--x-*` colour tokens, so
  dark mode follows the BareDOM theme."
  (:require ["@codemirror/commands" :refer [history historyKeymap standardKeymap]]
            ["@codemirror/language" :refer [bracketMatching HighlightStyle syntaxHighlighting]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView keymap]]
            ["@lezer/highlight" :refer [tags]]
            [nextjournal.clojure-mode :as cm-clj]
            [nextjournal.clojure-mode.commands :as commands]
            [nextjournal.clojure-mode.extensions.close-brackets :as close-brackets]
            [nextjournal.clojure-mode.extensions.selection-history :as sel-history]))

(def theme
  "The editor's look, from BareDOM's colour and font tokens."
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
                    ".cm-nonmatchingBracket"     {:color "var(--x-color-danger)"}})))

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

(def text-layer
  "The keys that edit text inside the editor.
  These are CodeMirror's standard keys and undo history, plus Enter and Tab, which indent
  through clojure-mode. clojure-mode's balanced-bracket keys come with its close-brackets
  extension. Editor commands, such as save, are matched by the page instead."
  (.of keymap (.concat #js [#js {:key "Enter" :run commands/enter-and-indent}
                            #js {:key "Tab" :run commands/indent}]
                       standardKeymap historyKeymap)))

(def extensions
  "The extensions every editor state gets.
  These are the undo history, [[text-layer]], clojure-mode's syntax, close brackets and
  selection history, CodeMirror's own bracket matching, [[highlighting]] and [[theme]].
  CodeMirror's bracket matching replaces clojure-mode's, which crashes on current
  CodeMirror versions. Nothing reformats while typing."
  #js [(history) text-layer (cm-clj/syntax) (close-brackets/extension) (sel-history/extension)
       (bracketMatching) highlighting theme])

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
