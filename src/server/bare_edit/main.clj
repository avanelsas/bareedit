(ns bare-edit.main
  "The `bareedit` command, which starts an editor for one project folder.
  The folder is the one named on the command line, or else the folder the command runs in.
  The editor reaches only that folder. Each project gets its own process."
  (:require [bare-edit.server :as server]
            [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def cli-spec
  "The command-line options."
  {:build   {:desc "The shadow-cljs build to evaluate ClojureScript in, when several run"
             :ref "<name>" :coerce :keyword}
   :no-open {:desc "Do not open the browser" :coerce :boolean}
   :version {:alias :v :desc "Print the version and stop" :coerce :boolean}
   :help    {:alias :h :desc "Print this help and stop" :coerce :boolean}})

(def usage
  "The help text: how to call the command, and its options."
  (str "Usage: bareedit [options] [folder]\n\n"
       "Starts BareEdit for the folder, or for the current folder.\n\n"
       "Options:\n"
       (cli/format-opts {:spec cli-spec :order [:build :no-open :version :help]})))

(defn parse-command
  "The command-line arguments `args` read against [[cli-spec]], as `{:opts :args}`.
  An option the spec does not name gives `{:problem :unknown-option :option name}` instead."
  [args]
  (try (cli/parse-args args {:spec cli-spec :restrict true})
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [cause option]} (ex-data e)]
           (if (= :restrict cause)
             {:problem :unknown-option :option option}
             (throw e))))))

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
  [{:keys [problem path option] n :count}]
  (case problem
    :too-many       (str "Give one project folder, not " n ".")
    :missing        (str "No such folder: " path)
    :not-a-folder   (str "Not a folder: " path)
    :unknown-option (str "Unknown option " (if (= 1 (count (name option))) "-" "--") (name option)
                         ". Run bareedit --help to see the options.")))

(defn- source-root
  "The folder of BareEdit's source code, when it runs from one, else `nil`.
  It is found from this namespace's file, `src/server/bare_edit/main.clj` in that folder."
  []
  (let [url (str (io/resource "bare_edit/main.clj"))]
    (when (str/starts-with? url "file:")
      (-> (fs/path (java.net.URI. url)) fs/parent fs/parent fs/parent fs/parent))))

(defn- git-version
  "The version of the git checkout at the folder `root`, as `describe (date)`, or `nil`.
  `describe` is the nearest tag, or the short commit when there is none."
  [root]
  (let [git (fn [& words] (let [{:keys [exit out]} (apply process/shell {:dir (str root) :out :string
                                                                         :err :string :continue true}
                                                          "git" words)]
                            (when (zero? exit) (str/trim out))))]
    (when-let [described (git "describe" "--tags" "--always")]
      (str described " (" (git "log" "-1" "--format=%cs") ")"))))

(def pom-properties
  "The resource where the jar keeps its Maven coordinate and version."
  "META-INF/maven/com.github.avanelsas/bareedit/pom.properties")

(defn properties-version
  "The `version` entry of the properties file text `text`, or `nil` when it has none."
  [text]
  (some (fn [line] (second (re-matches #"\s*version\s*=\s*(\S+)\s*" line)))
        (str/split-lines text)))

(defn version
  "BareEdit's version, as the command prints it.
  It is the version in the jar's [[pom-properties]], else the git version of the source
  folder it runs from, else `\"unknown\"`."
  []
  (or (some-> (io/resource pom-properties) slurp properties-version)
      (some-> (source-root) git-version)
      "unknown"))

(defn action
  "What the command does with the arguments `args`, run in the folder `cwd`, as a value.
  It is `{:action :fail :text text}`, `{:action :help}`, `{:action :version}`, or
  `{:action :start :root root :opts opts}`. An unknown option fails first. `--help` and
  `--version` come before the folder, which is checked only to start, see [[project-root]]."
  [cwd args]
  (let [{:keys [opts args problem] :as parsed} (parse-command args)]
    (cond
      problem         {:action :fail :text (problem-text parsed)}
      (:help opts)    {:action :help}
      (:version opts) {:action :version}
      :else           (let [{:keys [root problem] :as chosen} (project-root cwd args)]
                        (if problem
                          {:action :fail :text (problem-text chosen)}
                          {:action :start :root root :opts opts})))))

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
  stops. `--help` and `--version` print and stop, and an unknown option stops with a
  message."
  [& args]
  (let [{:keys [text root opts] what :action} (action (str (fs/cwd)) args)]
    (case what
      :fail    (fail! text)
      :help    (println usage)
      :version (println "bareedit" (version))
      :start   (let [{:keys [url stopping stop]} (server/start! (assoc opts :root root))]
                 (println "BareEdit for" root)
                 (println url)
                 (when-not (:no-open opts) (open-browser! url))
                 @stopping
                 (println "The browser tab closed, so BareEdit stopped.")
                 (stop)
                 (System/exit 0)))))
