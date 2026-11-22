(ns bare-edit.search
  "Finding text, as pure functions that the editor, the server and the demo share.
  A query is a map `{:text :case :regexp :word}`: the text to find, whether case matters,
  whether the text is a regular expression, and whether it matches whole words only. Each
  line is searched on its own, so a match never runs across lines."
  (:require [clojure.string :as str]))

(def max-matches
  "How many matching lines a search of the project gives at most."
  500)

(def max-line-length
  "How many characters of a matching line a search of the project gives back."
  200)

(def ^:private special
  "The characters that have a meaning in a regular expression, in Java and in JavaScript."
  #"[\\^$.|?*+()\[\]{}/]")

(defn- escape
  "The text `s` as a regular expression that matches exactly `s`."
  [s]
  (str/replace s special #(str "\\" %)))

(def blank-query
  "The query with no text and every option off."
  {:text "" :case false :regexp false :word false})

(defn query?
  "Whether `q` is a query: a map with the keys of [[blank-query]], text and three booleans."
  [q]
  (and (map? q) (string? (:text q)) (every? #(boolean? (get q %)) [:case :regexp :word])))

(defn pattern
  "The compiled pattern of the query `q`, as `{:pattern re}`, or `nil` when its text is empty.
  A regular expression that does not compile gives `{:problem sentence}` instead. Case is
  ignored unless `:case` is true, and `:word` puts a word boundary at either end. A
  regular expression follows the rules of the platform it runs on: Java's on the server
  and JavaScript's in the browser. The two agree on the common cases."
  [{:keys [text case regexp word]}]
  (when (seq text)
    (let [source (cond->> (if regexp text (escape text))
                   word (#(str "\\b(?:" % ")\\b")))]
      (try {:pattern #?(:clj  (java.util.regex.Pattern/compile
                               source (if case 0 (bit-or java.util.regex.Pattern/CASE_INSENSITIVE
                                                         java.util.regex.Pattern/UNICODE_CASE)))
                        :cljs (js/RegExp. source (if case "g" "gi")))}
           (catch #?(:clj Exception :cljs :default) _
             {:problem "The regular expression is not valid."})))))

(defn line-ranges
  "The places in `line` where `re` matches, as `[from to]` pairs, in order.
  `re` is a pattern of [[pattern]]. A match of no characters is left out. In the browser
  the search moves `re` through `line` and leaves it at the start again, so `re` can be
  shared but not used by two searches at once."
  [re line]
  #?(:clj  (let [m (re-matcher re line)]
             (loop [found []]
               (if (.find m)
                 (recur (cond-> found (< (.start m) (.end m)) (conj [(.start m) (.end m)])))
                 found)))
     :cljs (do (set! (.-lastIndex re) 0)
             (loop [found []]
               (if-let [m (.exec re line)]
                 (let [from (.-index m)
                       to   (+ from (count (aget m 0)))]
                   ;; a match of no characters would be found again at the same place
                   (when (= from to) (set! (.-lastIndex re) (inc to)))
                   (recur (cond-> found (< from to) (conj [from to]))))
                 ;; a search that finds nothing more leaves lastIndex at 0
                 found)))))

(defn found
  "The lines of `files` where `re` matches, at most `limit` of them.
  `files` is a sequence of `[path text]` pairs, searched in the order given, and `re` is a
  pattern of [[pattern]]. Returns `{:matches :more}`: `:matches` holds `{:path :line :text}`
  for each matching line, with its number from 1 and its whole text, and `:more` says
  whether lines past `limit` were left out. A lazy `files` is read only until the match
  after `limit`, or a chunk past it when the sequence is chunked."
  [re files limit]
  (let [hits (for [[path text] files
                   [i line]    (map-indexed vector (str/split-lines text))
                   :when (seq (line-ranges re line))]
               {:path path :line (inc i) :text line})
        [kept more] (split-at limit hits)]
    {:matches (vec kept) :more (boolean (seq more))}))

(defn excerpt
  "The line `text` of a match as a reply shows it, without its indentation.
  It is cut to [[max-line-length]] characters."
  [text]
  (let [shown (str/triml text)]
    (subs shown 0 (min max-line-length (count shown)))))

(defn search
  "The reply to the query `q` over `files`, `[path text]` pairs in the order to search them.
  The lines are those of [[found]], at most [[max-matches]], each shown as an [[excerpt]],
  which keeps the reply small. An empty query finds nothing. A query whose regular
  expression does not compile gives `{:problem sentence}`, see [[pattern]]."
  [q files]
  (let [{re :pattern problem :problem} (pattern q)]
    (cond problem   {:problem problem}
          (nil? re) {:matches [] :more false}
          :else     (update (found re files max-matches) :matches
                            (partial mapv #(update % :text excerpt))))))
