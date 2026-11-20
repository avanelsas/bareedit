(ns bare-edit.github-pages-demo.snapshot
  "The snapshot the read-only demo shows: a project's text files and the version they are from.
  A snapshot is `{:version :files}`, where `:files` maps each project-relative path to its
  text. It never changes, so its version is the version of every file in it."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(defn text?
  "Whether the bytes `bs` are text. A file with a zero byte is binary, such as an image."
  [bs]
  (not-any? zero? bs))

(defn snapshot
  "The snapshot of `files`, pairs of a project-relative path and its bytes, at `version`.
  `:files` is a sorted map of path to text, and binary files are left out, see [[text?]]."
  [version files]
  {:version version
   :files   (into (sorted-map) (for [[path bs] files :when (text? bs)]
                                 [path (String. ^bytes bs "UTF-8")]))})

(defn- git
  "The output of git with `args`, run in the folder `root`, without its last newline."
  [root & args]
  (str/trim-newline (:out (apply process/shell {:dir (str root) :out :string} "git" args))))

(defn of-repository
  "The snapshot of the git repository at `root`: the files git tracks, as they are on disk.
  Its version is what `git describe` names, the nearest tag or the commit, which ends in
  `-dirty` when the files on disk differ from the commit."
  [root]
  (snapshot (git root "describe" "--tags" "--always" "--dirty")
            (for [path (str/split-lines (git root "ls-files"))
                  :let [file (fs/path root path)]
                  :when (fs/regular-file? file)]
              [path (fs/read-all-bytes file)])))

(defn write!
  "Writes the snapshot of the git repository at `root` to `file`, as EDN."
  [root file]
  (spit (str file) (pr-str (of-repository root))))
