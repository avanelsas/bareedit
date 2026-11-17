(ns bare-edit.paths
  "Project-relative paths. No path in a request may lead outside the project folder."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn resolve-in
  "The canonical file for the project-relative path `rel` under the folder `root`, or `nil`.
  It is `nil` when `rel` is blank or absolute, names `root` itself, or leads outside it
  through `..` or a symlink. Paths are compared by whole segments, so `/proj-other` is not
  inside `/proj`."
  [root rel]
  (when (and (string? rel) (not (str/blank? rel)) (not (fs/absolute? rel)))
    (let [root (fs/canonicalize root)
          file (fs/canonicalize (fs/path root rel))]
      (when (and (fs/starts-with? file root) (not= file root))
        file))))

(defn relative
  "The path of `file` relative to the folder `root`, with forward slashes."
  [root file]
  (str/replace (str (fs/relativize (fs/canonicalize root) file)) "\\" "/"))
