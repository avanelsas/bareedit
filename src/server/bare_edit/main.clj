(ns bare-edit.main
  "The `bare-edit` command, which starts an editor for the folder it runs in.
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

(defn- open-browser!
  "Opens `url` in the default browser, with `open` on macOS and `xdg-open` elsewhere."
  [url]
  (let [mac? (re-find #"(?i)mac" (System/getProperty "os.name"))]
    (process/process [(if mac? "open" "xdg-open") url])))

(defn -main
  "Starts the server for the current folder, prints its URL and opens it in the browser.
  `args` are the command-line options in [[cli-spec]]. It runs until the process stops."
  [& args]
  (let [opts (cli/parse-opts args {:spec cli-spec})
        {:keys [url]} (server/start! (assoc opts :root (str (fs/cwd))))]
    (println "BareEditClj for" (str (fs/cwd)))
    (println url)
    (when-not (:no-open opts) (open-browser! url))
    @(promise)))
