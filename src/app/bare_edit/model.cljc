(ns bare-edit.model
  "Pure functions over plain values for the editor's files.
  They build the file tree, rank the file palette, check the path of a new file, and decide
  what happens to a file changed on disk."
  (:require [clojure.string :as str]))

(defn tree-items
  "The `x-tree` items for `paths`, a flat, sorted list of project-relative file paths.
  Folders come before files. Every item's id is its path, so a selected id is the file to
  open."
  [paths]
  (letfn [(insert [node [part & more]]
            (if more
              (update-in node [part :children] #(insert (or % (sorted-map)) more))
              (assoc-in node [part :file?] true)))
          (items [prefix node]
            (let [{folders false files true} (group-by (comp boolean :file? val) node)]
              (vec (concat
                    (for [[nm {:keys [children]}] folders
                          :let [id (str prefix nm)]]
                      {:id id :label nm :children (items (str id "/") children)})
                    (for [[nm _] files]
                      {:id (str prefix nm) :label nm})))))]
    (items "" (reduce #(insert %1 (str/split %2 #"/")) (sorted-map) paths))))

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

(defn- tier
  "How well `path` matches `word`, the last word of the query, best first.
  1 when the file name without its extension equals it, 2 when it starts with it, 3 when it
  contains it, and 4 when the match is elsewhere in the path. The word counts as typed,
  converted, and as the last part of a namespace name, so `bare-edit.steps` ranks
  `steps.cljs` first."
  [path word]
  (let [b  (base-name path)
        c  (converted word)
        ws [word c (last (str/split c #"/"))]]
    (cond (some #(= b %) ws)                  1
          (some #(str/starts-with? b %) ws)   2
          (some #(str/includes? b %) ws)      3
          :else                               4)))

(defn rank-paths
  "The best `limit` paths in `paths` for the file palette's `query`, best first.
  Every space-separated word of the query must appear in the path, ignoring case, as typed
  or with `.` as `/` and `-` as `_`. Paths rank by how their file name matches the last
  word, then shorter first, then alphabetically. An empty query lists the shortest paths."
  [paths query limit]
  (let [words (remove str/blank? (str/split (str/lower-case (str/trim (or query ""))) #"\s+"))
        match? (fn [p] (let [lp (str/lower-case p)]
                         (every? #(or (str/includes? lp %) (str/includes? lp (converted %))) words)))
        order  (if (seq words)
                 (let [w (last words)] (fn [p] [(tier p w) (count p) p]))
                 (fn [p] [(count p) p]))]
    (into [] (take limit) (sort-by order (filter match? paths)))))

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

(defn file-name
  "The last segment of `path`, the file's name."
  [path]
  (last (str/split path #"/")))
