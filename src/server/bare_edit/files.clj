(ns bare-edit.files
  "Files on disk: the path list, reading, and compare-and-set saving.
  A file's version is the SHA-256 of its bytes, or `:absent` when it does not exist. A save
  states the version it expects, so it never overwrites a change made outside the editor."
  (:require [bare-edit.paths :as paths]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;; ── Versions ──────────────────────────────────────────────────────────────

(defn version-of-bytes
  "The version of a file holding the bytes `bs`: their SHA-256, as hex."
  [^bytes bs]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" %) digest))))

(defn version
  "The version of `file`: the hash of its bytes, or `:absent` when it is not a regular file."
  [file]
  (if (fs/regular-file? file)
    (version-of-bytes (fs/read-all-bytes file))
    :absent))

;; ── The path list ──────────────────────────────────────────────────────────

(def skipped
  "The folders the walk skips outside a git repository, since they hold generated files."
  #{".git" "node_modules" "target" ".cpcache" ".shadow-cljs"})

(defn- git-paths
  "The paths git knows in the repository at `root`, or `nil` when `root` is not in one.
  These are the tracked files plus the untracked files that are not ignored, so the
  project's own `.gitignore` decides what is left out. The `-z` flag keeps paths with
  special characters intact. A merge conflict can list a path twice. Throws when the `git`
  program cannot be run."
  [root]
  (let [{:keys [exit out]} (process/shell {:dir (str root) :out :string :err :string :continue true}
                                          "git" "ls-files" "-z" "-co" "--exclude-standard")]
    (when (zero? exit)
      (remove str/blank? (str/split out #"\u0000")))))

(defn- walked-paths
  "Every regular file under the folder `root`, as project-relative paths.
  The walk does not descend into the [[skipped]] folders or follow symlinked folders."
  [root]
  (let [root (fs/canonicalize root)]
    (letfn [(walk [dir]
              (mapcat (fn [p]
                        (cond (fs/directory? p {:nofollow-links true})
                              (when-not (skipped (str (fs/file-name p))) (walk p))
                              (fs/regular-file? p) [(paths/relative root p)]))
                      (fs/list-dir dir)))]
      (walk root))))

(defn path-list
  "Every file in the project at `root`, as sorted project-relative paths.
  In a git repository, git lists them; otherwise the folder is walked."
  [root]
  (->> (or (git-paths root) (walked-paths root))
       ;; drops tracked files deleted on disk, and symlinks that lead outside the project
       (filter #(some-> (paths/resolve-in root %) fs/regular-file?))
       distinct
       sort
       vec))

;; ── Reading and saving ──────────────────────────────────────────────────────

(defn read-file
  "The file at the project-relative path `rel` under `root`, as `{:text :version}`.
  A missing file is `{:text \"\" :version :absent}`."
  [root rel]
  (let [file (paths/resolve-in root rel)]
    (if (fs/regular-file? file)
      (let [bs (fs/read-all-bytes file)]
        {:text (String. ^bytes bs "UTF-8") :version (version-of-bytes bs)})
      {:text "" :version :absent})))

(def ^:private save-lock
  "The lock that makes a save's version check and write one step within this server."
  (Object.))

(def ^:private posix?
  "Whether the file system has POSIX permissions, which a save must carry over."
  (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix"))

(defn- temp-file-for
  "A new, empty temporary file next to `file`, with the permissions a save should leave.
  It is created like any new file, so it gets the usual permissions under the umask, not
  the owner-only ones of a temp file. When `file` exists, its permissions are copied, so a
  save keeps a script executable."
  [file]
  (let [tmp (fs/create-file (fs/path (fs/parent file) (str ".bare-edit-" (random-uuid) ".tmp")))]
    (when (and posix? (fs/exists? file))
      (fs/set-posix-file-permissions tmp (fs/posix-file-permissions file)))
    tmp))

(defn save!
  "Writes `text` to `rel` under `root` when the file still has the version `expected`.
  Returns `{:version new}`, or `{:conflict current}` when the file changed on disk. It
  writes through a temporary file and an atomic move, so a reader never sees half a file,
  and removes the temporary file if the write fails. The file keeps its permissions, and a
  new one gets the usual ones. Missing folders are created, so a new file can be saved
  anywhere in the project. Throws when `rel` is a folder."
  [root rel text expected]
  (locking save-lock
    (let [file    (paths/resolve-in root rel)
          current (version file)]
      ;; fs/move would put the temporary file inside a folder, like mv
      (when (fs/directory? file) (throw (ex-info (str rel " is a folder.") {:path rel})))
      (if (not= current expected)
        {:conflict current}
        (let [bs  (.getBytes ^String text "UTF-8")
              _   (fs/create-dirs (fs/parent file))
              tmp (temp-file-for file)]
          (try (fs/write-bytes tmp bs)
               (fs/move tmp file {:replace-existing true :atomic-move true})
               (finally (fs/delete-if-exists tmp)))
          {:version (version-of-bytes bs)})))))
