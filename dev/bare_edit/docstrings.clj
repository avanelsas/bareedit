(ns bare-edit.docstrings
  "Checks the docstrings of Clojure source files against `.claude/rules/docstrings.md`.
  It covers the rules a program can check: every definition has a docstring, its first
  line is one capitalized sentence within 100 columns, it has no leading or trailing
  whitespace, continuation lines line up with the opening quote, every positional argument
  is named in backticks, and nothing refers to the spec or design documents. Whether a
  docstring is accurate still needs a reader. Run it with `bb docstrings`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def checked-forms
  "The definition forms that must have a docstring. `deftest` is exempt."
  #{"ns" "def" "defn" "defn-" "defmulti" "defonce" "defmacro" "defprotocol"})

(def max-column
  "The last column a docstring's first line may reach."
  100)

(def outside-reference
  "A pattern for references to documents outside the repository.
  These are spec and design decision numbers, a `D` or an `L` followed by a number, review
  decisions, the state section of the spec, and plan stages."
  #"\b[DL]\d{1,2}\b|[Dd]ecision [A-D]\b|Where state lives|design L\d|[Ss]tage \d")

(defn- docstring-loc
  "The location of the docstring in the definition whose name is at `name-loc`, or `nil`.
  `head` is the form's name, such as `\"defn\"`. In a `def` a string is a docstring only
  when a value follows it."
  [head name-loc]
  (let [nxt (z/right name-loc)]
    (when (and nxt
               (#{:token :multi-line} (z/tag nxt))
               (string? (z/sexpr nxt))
               (or (not= head "def") (z/right nxt)))
      nxt)))

(defn- argument-names
  "The names of the positional arguments in the argument vector `arglist`.
  Names starting with `_` are left out, and so are destructured map keys; a map counts by
  its `:as` name."
  [arglist]
  (letfn [(walk [x]
            (cond (symbol? x) (when-not (or (= '& x) (str/starts-with? (name x) "_")) [(name x)])
                  (vector? x) (mapcat walk x)
                  (map? x)    (some-> (:as x) walk)
                  :else       nil))]
    (walk arglist)))

(defn- arglists
  "The argument vectors of the `defn` or `defmacro` form `form`, for one arity or several."
  [form]
  (let [body (drop 2 form)]
    (if-let [single (first (filter vector? body))]
      [single]
      (keep #(when (and (list? %) (vector? (first %))) (first %)) body))))

(defn- indentation-problems
  "The problems with the continuation lines of `raw`, a docstring's source text.
  Each must start at `column`, the 0-based column of the opening quote. A line in a
  Markdown list or code block may be indented further."
  [raw column]
  (loop [[line & more] (rest (str/split-lines (subs raw 1 (dec (count raw))))), nested? false, found []]
    (if-not line
      found
      (let [indent  (count (re-find #"^ *" line))
            text    (str/trim line)
            fence?  (str/starts-with? text "```")
            nested? (or (str/starts-with? text "- ") (and nested? (not (str/blank? text))) fence?)
            bad?    (and (not (str/blank? line))
                         (or (< indent column) (and (> indent column) (not nested?))))]
        (recur more nested?
               (cond-> found
                 bad? (conj (str "continuation line indented " indent ", want " column ": " text))))))))

(defn- definition-problems
  "The problems with the definition at `loc`, whose name is `head`, as sentences."
  [loc head]
  (let [name-loc (z/right (z/down loc))
        doc-loc  (docstring-loc head name-loc)
        doc      (if doc-loc
                   (z/sexpr doc-loc)
                   (when (= :meta (z/tag name-loc)) (:doc (meta (z/sexpr name-loc)))))]
    (if-not (string? doc)
      ["no docstring"]
      (let [first-line (str/trim (first (str/split-lines doc)))
            raw        (some-> doc-loc z/string)
            [_ col]    (some-> doc-loc z/position)]
        (cond-> []
          (not= doc (str/trim doc))
          (conj "leading or trailing whitespace")

          (not (re-matches #"[`\[A-Z].*[.?:]" first-line))
          (conj (str "first line is not one capitalized sentence: " first-line))

          (and raw (> (+ col -1 (count (first (str/split-lines raw)))) max-column))
          (conj (str "first line runs past column " max-column))

          raw
          (into (indentation-problems raw (dec col)))

          (re-find outside-reference doc)
          (conj (str "refers to a document outside the repository: " (re-find outside-reference doc)))

          (#{"defn" "defn-" "defmacro"} head)
          (into (for [args (arglists (z/sexpr loc))
                      a    (argument-names args)
                      :when (not (str/includes? doc (str "`" a "`")))]
                  (str "argument `" a "` is not named in backticks"))))))))

(defn file-problems
  "The docstring problems in the Clojure source file at `path`.
  Returns a vector of `{:file :line :var :problem}` maps, empty when the file is fine."
  [path]
  (loop [loc (z/of-file (str path) {:track-position? true}), found []]
    (if (or (nil? loc) (z/end? loc))
      found
      (let [head (when (= :list (z/tag loc)) (some-> loc z/down z/sexpr str))
            found (if (checked-forms head)
                    (let [[line _] (z/position loc)
                          var-name (some-> loc z/down z/right z/sexpr str)]
                      (into found (map (fn [p] {:file (str path) :line line :var var-name :problem p}))
                            (definition-problems loc head)))
                    found)]
        (recur (z/right loc) found)))))

(defn source-files
  "The Clojure source files under the folders `dirs`, sorted."
  [dirs]
  (->> dirs
       (mapcat #(fs/glob % "**.{clj,cljs,cljc}"))
       (map str)
       sort))

(defn -main
  "Checks every source file under the folders in `args`, or under the default folders.
  The default folders are `src`, `test`, `dev` and `github-pages-demo`.
  Prints each problem and exits with status 1 when there are any, so CI can run it."
  [& args]
  (let [problems (mapcat file-problems (source-files (or (seq args) ["src" "test" "dev" "github-pages-demo"])))]
    (doseq [{:keys [file line var problem]} problems]
      (println (str file ":" line " " var ": " problem)))
    (println (case (count problems)
               0 "All docstrings follow the rule."
               1 "1 docstring problem."
               (str (count problems) " docstring problems.")))
    (System/exit (if (seq problems) 1 0))))
