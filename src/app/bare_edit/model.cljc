(ns bare-edit.model
  "Pure functions over plain values for the editor's files.
  They build the file tree, rank the file palette, check the path of a new file, and decide
  what happens to a file changed on disk."
  (:require [clojure.string :as str]))

(defn- name-pieces
  "The runs of digits and the runs of other characters in the name `nm`, in order."
  [nm]
  (re-seq #"\d+|\D+" nm))

(defn- piece-key
  "What the name piece `s` sorts by: `[number text]`.
  For a run of digits, `number` is `[length digits]` with leading zeros dropped, so
  `\"007\"` and `\"7\"` are equal and any length works. For other text it is `nil`.
  `text` is the piece in lower case."
  [s]
  [(when (re-matches #"\d+" s)
     (let [d (str/replace s #"^0+(?=\d)" "")] [(count d) d]))
   (str/lower-case s)])

(defn- name-key
  "What the file name `nm` sorts by in [[name-order]], as `[piece-keys nm]`.
  The name itself breaks a tie."
  [nm]
  [(mapv piece-key (name-pieces nm)) nm])

(defn- compare-piece-keys
  "Compares the piece keys `[na ta]` and `[nb tb]`, by number when both have one.
  The numbers `na` and `nb` are there for runs of digits. Otherwise the texts `ta` and `tb`
  compare."
  [[na ta] [nb tb]]
  (if (and na nb) (compare na nb) (compare ta tb)))

(defn- compare-name-keys
  "Compares the name keys `[pa a]` and `[pb b]` from [[name-key]] in [[name-order]].
  The piece keys `pa` and `pb` compare first, then the names `a` and `b`."
  [[pa a] [pb b]]
  (let [c (or (first (remove zero? (map compare-piece-keys pa pb)))
              (compare (count pa) (count pb)))]
    (if (zero? c) (compare a b) c)))

(defn name-order
  "Compares the file names `a` and `b` in the order the file tree shows them.
  Case is ignored and runs of digits compare as numbers, so `file2` comes before `file10`
  and `apple` before `README`. Names equal in that order compare by character code, so the
  order is the same on every run."
  [a b]
  (compare-name-keys (name-key a) (name-key b)))

(defn- sort-names
  "The items `xs` sorted in [[name-order]] of their names, splitting each name only once.
  `f` gives an item's name."
  [f xs]
  (->> xs
       (map (fn [x] [(name-key (f x)) x]))
       (sort-by first compare-name-keys)
       (map second)))

(defn tree-items
  "The `x-tree` items for `paths`, a list of project-relative file paths.
  In each folder, folders come before files, each in [[name-order]]. Every item's id is its
  path, so a selected id is the file to open."
  [paths]
  (letfn [(insert [node [part & more]]
            (if more
              (update-in node [part :children] insert more)
              (assoc-in node [part :file?] true)))
          (items [prefix node]
            (let [{folders false files true} (update-vals (group-by (comp boolean :file? val) node)
                                                          #(sort-names key %))]
              (vec (concat
                    (for [[nm {:keys [children]}] folders
                          :let [id (str prefix nm)]]
                      {:id id :label nm :children (items (str id "/") children)})
                    (for [[nm _] files]
                      {:id (str prefix nm) :label nm})))))]
    (items "" (reduce #(insert %1 (str/split %2 #"/")) {} paths))))

(defn disk-action
  "What to do with an open buffer whose file on disk now has the version `version`.
  `unsaved?` says whether the buffer has unsaved changes, and `known-version` is the
  version it last read or saved. One rule holds, whoever changed the file. Returns `:none`
  when the version is unchanged. A changed file gives `:reload`, or `:ask` (keep mine or
  take disk) when the buffer has unsaved changes. A deleted file gives `:close`, or
  `:mark-deleted` when the buffer has unsaved changes, so the next save creates it again."
  [unsaved? known-version version]
  (cond (= version known-version) :none
        (= version :absent)       (if unsaved? :mark-deleted :close)
        unsaved?                  :ask
        :else                     :reload))

(defn- converted
  "The query word `w` with `.` as `/` and `-` as `_`, so a namespace name finds its file."
  [w]
  (-> w (str/replace "." "/") (str/replace "-" "_")))

(defn- base-name
  "The file name of `path` without its extension, in lower case."
  [path]
  (let [f (str/lower-case (last (str/split path #"/")))
        i (str/last-index-of f ".")]
    (if (and i (pos? i)) (subs f 0 i) f)))

(defn- word-forms
  "The forms of the query word `word` that a file name is matched against.
  They are the word as typed, converted, and as the last part of a namespace name, so
  `bare-edit.steps` ranks `steps.cljs` first."
  [word]
  (let [c (converted word)]
    [word c (last (str/split c #"/"))]))

(defn- tier
  "How well `path` matches `forms`, the [[word-forms]] of the query's last word, best first.
  1 when the file name without its extension equals one, 2 when it starts with one, 3 when
  it contains one, and 4 when the match is elsewhere in the path."
  [path forms]
  (let [b (base-name path)]
    (cond (some #(= b %) forms)                1
          (some #(str/starts-with? b %) forms) 2
          (some #(str/includes? b %) forms)    3
          :else                                4)))

(defn rank-paths
  "The best `limit` paths in `paths` for the file palette's `query`, best first.
  Every space-separated word of the query must appear in the path, ignoring case, as typed
  or with `.` as `/` and `-` as `_`. Paths rank by how their file name matches the last
  word, then shorter first, then in [[name-order]]. An empty query lists the shortest paths."
  [paths query limit]
  (let [words  (remove str/blank? (str/split (str/lower-case (str/trim (or query ""))) #"\s+"))
        word-pairs (mapv (fn [w] [w (converted w)]) words)
        match?     (fn [lp] (every? (fn [[w c]] (or (str/includes? lp w) (str/includes? lp c))) word-pairs))
        forms      (some-> (last words) word-forms)
        score      (fn [p] [(if forms (tier p forms) 0) (count p)])]
    ;; the paths sort by score; names are compared only among equal scores, and only in the
    ;; groups that reach the first `limit`
    (->> paths
         (keep (fn [p] (when (match? (str/lower-case p)) [(score p) p])))
         (sort-by first)
         (partition-by first)
         (mapcat #(sort-names identity (map second %)))
         (into [] (take limit)))))

(defn path-problem
  "Why `path` cannot be a new file in the project, as a sentence, or `nil` when it can.
  The path must not be empty, absolute, or lead outside the project through `..`, and it
  must end with a file name. The server checks again when the file is saved."
  [path]
  (let [p (str/trim (or path ""))]
    (cond (str/blank? p)                                     "Enter a path."
          (or (str/starts-with? p "/") (re-find #"^[A-Za-z]:" p)
              (some #{".."} (str/split p #"/")))             "Use a path inside the project."
          (re-find #"(^|/)\.?$" p)                          "Add a file name.")))

(defn normalize-path
  "The path `path` without spaces around it, empty parts and `.` parts.
  So `./src//a.clj` is `src/a.clj`, the form the path list uses."
  [path]
  (str/join "/" (remove #{"" "."} (str/split (str/trim path) #"/"))))

(defn folder-of
  "The folder of `path` with a trailing `/`, or `\"\"` for a file at the top or no path."
  [path]
  (if-let [i (some-> path (str/last-index-of "/"))]
    (subs path 0 (inc i))
    ""))

(defn settings?
  "Whether `path` names the settings file, which is not in the project.
  Every other path is a project-relative path, a string."
  [path]
  (= :settings path))

(defn file-name
  "The last segment of `path`, the file's name.
  The settings file is named `config.edn`, as on disk, see [[settings?]]."
  [path]
  (if (settings? path)
    "config.edn"
    (last (str/split path #"/"))))

(def languages
  "The editor's language for each file extension, without the dot.
  The settings file is EDN, so Clojure. Any other file is plain text."
  {"clj" :clojure "cljs" :clojure "cljc" :clojure "edn" :clojure "bb" :clojure
   "md" :markdown "markdown" :markdown})

(def language-names
  "The name the status bar shows for each language that has one."
  {:markdown "Markdown"})

(defn language-of
  "The editor's language for the file at `path`: `:clojure`, `:markdown` or `:text`.
  See [[languages]]. The settings file, whose path is `:settings`, is EDN."
  [path]
  (if (settings? path)
    :clojure
    (let [nm  (file-name path)
          dot (str/last-index-of nm ".")]
      (get languages (when (and dot (pos? dot)) (str/lower-case (subs nm (inc dot)))) :text))))

(defn ns-of
  "The namespace named by the `ns` form in the Clojure source `text`, or `nil`.
  Metadata before the name, such as `^:no-doc`, is skipped."
  [text]
  (second (re-find #"\(\s*ns\s+(?:\^\S+\s+)*([^\s()\[\]{}\"^;]+)" text)))

(defn form-head
  "The start of the code `code`, for a one-line heading: its first line, cut at `n` characters."
  [code n]
  (let [line (str/trim (first (str/split-lines (str code))))]
    (if (> (count line) n) (str (subs line 0 n) "…") line)))
