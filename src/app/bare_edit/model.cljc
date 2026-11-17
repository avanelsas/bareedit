(ns bare-edit.model
  "Pure functions over plain values: the file tree and the rule for files changed on disk."
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

(defn file-name
  "The last segment of `path`, the file's name."
  [path]
  (last (str/split path #"/")))
