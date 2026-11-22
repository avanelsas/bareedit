(ns bare-edit.files
  "Files on disk: the path list, reading, and compare-and-set saving and renaming.
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

(defn- entries
  "The entries of the folder `dir`, or `nil` when it cannot be listed."
  [dir]
  (try (fs/list-dir dir)
       (catch java.io.IOException _ nil)))

(defn- walked
  "Every regular file under the folder `root`, and every folder the walk could not list.
  Returns `{:paths :unreadable}`, both project-relative, with `\".\"` for `root` itself. The
  walk does not descend into the [[skipped]] folders or follow symlinked folders. A folder
  it cannot list is left out, and the rest of the walk goes on."
  [root]
  (let [root (fs/canonicalize root)
        rel  (fn [p] (let [r (paths/relative root p)] (if (= "" r) "." r)))]
    (letfn [(walk [found dir]
              (if-let [listed (entries dir)]
                (reduce (fn [found p]
                          (cond (fs/directory? p {:nofollow-links true})
                                (cond-> found (not (skipped (str (fs/file-name p)))) (walk p))
                                (fs/regular-file? p) (update found :paths conj (rel p))
                                :else                found))
                        found listed)
                (update found :unreadable conj (rel dir))))]
      (walk {:paths [] :unreadable []} root))))

(defn listing
  "The files of the project at `root`, and the folders that could not be read.
  Returns `{:paths :unreadable}`, each a sorted vector of project-relative paths. In a git
  repository, git lists the files and `:unreadable` is empty; otherwise the folder is
  walked."
  [root]
  (let [{:keys [paths unreadable]} (if-let [listed (git-paths root)]
                                     {:paths listed :unreadable []}
                                     (walked root))]
    {:paths      (->> paths
                      ;; drops tracked files deleted on disk, and symlinks that lead outside the project
                      (filter #(some-> (paths/resolve-in root %) fs/regular-file?))
                      distinct
                      sort
                      vec)
     :unreadable (vec (sort unreadable))}))

;; ── Reading and saving ──────────────────────────────────────────────────────

(def max-search-bytes
  "The size above which a file is left out of a search, in bytes."
  (* 1024 1024))

(defn texts
  "The text of each file of the project at `root` that a search reads, as `[path text]` pairs.
  The files are those of [[listing]], in its order of paths. A file larger than
  [[max-search-bytes]] is left out, and so is a binary file, one that holds a zero byte.
  The pairs are read lazily, a chunk at a time, so a search that stops early reads few
  files past the last it needs."
  [root]
  (for [rel (:paths (listing root))
        :let [file (paths/resolve-in root rel)]
        :when (and file (<= (fs/size file) max-search-bytes))
        :let [^bytes bs (fs/read-all-bytes file)]
        :when (not-any? zero? bs)]
    [rel (String. bs "UTF-8")]))

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
  (let [tmp (fs/create-file (fs/path (fs/parent file) (str ".bareedit-" (random-uuid) ".tmp")))]
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

(defn- move-to-another-case!
  "Moves the file `src` to `dst`, the same file with its name in another case.
  A move onto the same file does nothing, so it goes through a temporary name next to it.
  When the second step fails, the file is moved back to `src` and the failure is thrown, so
  the file never stays under the temporary name."
  [src dst]
  (let [tmp (fs/path (fs/parent src) (str ".bareedit-" (random-uuid) ".tmp"))]
    (fs/move src tmp)
    (try (fs/move tmp dst)
         (catch Exception e
           (fs/move tmp src)
           (throw e)))))

(defn rename!
  "Moves the file at `from` to `to`, both under `root`, when it still has the version `expected`.
  Returns `{:version v}`, the unchanged version, or `{:conflict current}` when the file
  changed on disk or is gone, or `{:exists true}` when another file is at `to` already, so
  a rename never overwrites. On a file system that ignores case, `to` may be `from` in
  another case, which is the same file, see [[move-to-another-case!]]. Missing folders of
  `to` are created. It holds the save lock, so a save of the same file waits for it."
  [root from to expected]
  (locking save-lock
    (let [src     (paths/resolve-in root from)
          ;; the folder is resolved, but the name stays as typed: resolving it would give
          ;; back the case of a file that is already there
          dst     (fs/path (fs/parent (paths/resolve-in root to)) (fs/file-name to))
          current (version src)]
      (cond
        (not= current expected)
        {:conflict current}

        (and (fs/exists? dst) (not (fs/same-file? src dst)))
        {:exists true}

        :else
        (do (fs/create-dirs (fs/parent dst))
            (if (fs/exists? dst)
              (move-to-another-case! src dst)
              (fs/move src dst))
            {:version current})))))
