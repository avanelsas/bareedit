(ns bare-edit.keys
  "Matches key events to commands, as pure functions.
  A key event, as a map, and a command's `:keys` both become the same canonical string in
  CodeMirror's key syntax, so matching is a map lookup. The cases it must get right are the
  table in `bare-edit.keys-test`."
  (:require [clojure.string :as str]))

(def ^:private code-keys
  "The character of each punctuation key, by its `event.code`."
  {"BracketLeft" "[" "BracketRight" "]" "Period" "." "Slash" "/" "Comma" "," "Semicolon" ";"
   "Quote" "'" "Backquote" "`" "Minus" "-" "Equal" "=" "Backslash" "\\"})

(defn- base-from-code
  "The unshifted character of the physical key `code`, such as `\"KeyA\"`, or `nil`."
  [code]
  (cond (re-matches #"Key[A-Z]" code)   (str/lower-case (subs code 3))
        (re-matches #"Digit[0-9]" code) (subs code 5)
        :else                           (code-keys code)))

(defn- canonical
  "The canonical form of the key `k` with the modifiers in `mods`.
  `mods` is a map of `:alt`, `:ctrl`, `:meta` and `:shift`. Modifiers come in the order
  Shift, Meta, Ctrl, Alt. Shift is dropped for a non-letter character taken from
  `event.key`, which already includes it, unless `keep-shift?`."
  [{:keys [alt ctrl meta shift] :as _mods} k keep-shift?]
  (let [shifted-char? (and (not keep-shift?) (= 1 (count k)) (not= k " ")
                           (not (re-matches #"[a-z]" k)))]
    (cond->> (if (= k " ") "Space" k)
      alt                              (str "Alt-")
      ctrl                             (str "Ctrl-")
      meta                             (str "Meta-")
      (and shift (not shifted-char?))  (str "Shift-"))))

(defn event->key
  "The canonical key for the key event `ev`, a map of `:key :code :alt :ctrl :meta :shift`.
  `mac?` says whether Alt changes characters. The physical key from `:code` is used when
  Alt is held on macOS, or Ctrl with a non-letter, since `:key` then holds another
  character."
  [mac? {:keys [key code alt ctrl] :as ev}]
  (let [one?      (= 1 (count key))
        ctrl-sym? (and ctrl one? (not= key " ") (not (re-matches #"[a-zA-Z]" key)))
        physical  (when (and one? (or (and mac? alt) ctrl-sym?)) (base-from-code code))
        k         (or physical (if (and one? (re-matches #"[A-Z]" key)) (str/lower-case key) key))]
    (canonical ev k (some? physical))))

(defn spec->key
  "The canonical key for `spec`, a `:keys` string such as `\"Mod-Shift-Enter\"`.
  `Mod` means Meta when `mac?` is true and Ctrl otherwise."
  [mac? spec]
  (let [parts (str/split spec #"-(?!$)")
        k     (last parts)
        mods  (reduce (fn [m p] (case p
                                  "Mod"          (assoc m (if mac? :meta :ctrl) true)
                                  ("Cmd" "Meta") (assoc m :meta true)
                                  (assoc m (keyword (str/lower-case p)) true)))
                      {} (butlast parts))]
    (canonical mods (cond (= k "Space") " " (= 1 (count k)) (str/lower-case k) :else k) true)))

(defn key-index
  "A map from canonical key to command, for every key of every command in `commands`.
  `mac?` decides what `Mod` means."
  [mac? commands]
  (into {} (for [c commands
                 :let [ks (:keys c)]
                 k (if (string? ks) [ks] ks)]
             [(spec->key mac? k) c])))
