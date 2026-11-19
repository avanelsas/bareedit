(ns bare-edit.main
  "The `bareedit` command, which starts an editor for one project folder.
  The folder is the one named on the command line, or else the folder the command runs in.
  The editor reaches only that folder. Each project gets its own process."
  (:require [bare-edit.server :as server]
            [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as process]))

(def cli-spec
  "The command-line options."
  {:build   {:desc "The shadow-cljs build to evaluate ClojureScript in, when several run"
             :coerce :keyword}
   :no-open {:desc "Do not open the browser" :coerce :boolean}})

(defn project-root
  "The project folder for the command-line arguments `args`, read against the folder `cwd`.
  Returns `{:root path}` with the canonical path, or a problem: `{:problem :too-many :count n}`,
  `{:problem :missing :path arg}` or `{:problem :not-a-folder :path arg}`. No argument
  means `cwd`. The answer depends on the filesystem, which it reads."
  [cwd args]
  (let [[arg & more] args
        path         (fs/path cwd (or arg "."))]
    (cond
      more                       {:problem :too-many :count (count args)}
      (not (fs/exists? path))    {:problem :missing :path arg}
      (not (fs/directory? path)) {:problem :not-a-folder :path arg}
      :else                      {:root (str (fs/canonicalize path))})))

(defn problem-text
  "The message for `problem`, a problem from [[project-root]]."
  [{:keys [problem path] n :count}]
  (case problem
    :too-many     (str "Give one project folder, not " n ".")
    :missing      (str "No such folder: " path)
    :not-a-folder (str "Not a folder: " path)))

(defn- open-browser!
  "Opens `url` in the default browser, with `open` on macOS and `xdg-open` elsewhere."
  [url]
  (let [mac? (re-find #"(?i)mac" (System/getProperty "os.name"))]
    (process/process [(if mac? "open" "xdg-open") url])))

(defn- fail!
  "Prints `text` to standard error and ends the process with status 1."
  [text]
  (binding [*out* *err*] (println "bareedit:" text))
  (System/exit 1))

(defn -main
  "Starts the server for the project folder, prints its URL and opens it in the browser.
  `args` are the command-line options in [[cli-spec]] and at most one folder, see
  [[project-root]]. It runs until the last browser tab on it has closed, or the process
  stops."
  [& args]
  (let [{:keys [opts args]}           (cli/parse-args args {:spec cli-spec})
        {:keys [root problem] :as chosen} (project-root (str (fs/cwd)) args)]
    (if problem
      (fail! (problem-text chosen))
      (let [{:keys [url stopping stop]} (server/start! (assoc opts :root root))]
        (println "BareEdit for" root)
        (println url)
        (when-not (:no-open opts) (open-browser! url))
        @stopping
        (println "The browser tab closed, so BareEdit stopped.")
        (stop)
        (System/exit 0)))))
