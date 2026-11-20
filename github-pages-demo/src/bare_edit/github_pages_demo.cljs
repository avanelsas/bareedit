(ns bare-edit.github-pages-demo
  "The read-only demo of BareEdit for GitHub Pages.
  It runs the editor's own page, with [[bare-edit.github-pages-demo.server/answer]] in
  place of the server. The project is a snapshot of the BareEdit repository, which the page
  loads from [[snapshot-url]]."
  (:require [bare-edit.app :as app]
            [bare-edit.github-pages-demo.server :as server]
            [cljs.reader :as edn]))

(def snapshot-url
  "Where the snapshot is, next to the page, as EDN."
  "github-pages-demo.edn")

(defn- load-snapshot
  "A Promise of the snapshot at [[snapshot-url]].
  When it cannot be loaded, the Promise holds `{:problem message}`, which says why."
  []
  (-> (js/fetch snapshot-url)
      (.then (fn [^js r] (if (.-ok r)
                           (.text r)
                           (throw (js/Error. (str "the server answered " (.-status r)))))))
      (.then edn/read-string)
      (.catch (fn [^js e] {:problem (str "Could not load the demo's files: " (.-message e) ".")}))))

(defn connect
  "Returns a function that sends a request to the stand-in server, passing its events to `dispatch`.
  The stand-in is ready, and `dispatch` gets `[:connected]`, once the snapshot is loaded or
  known to be missing. Each reply comes as `[:message text]`, after the request has been
  sent, as it would from a server."
  [dispatch]
  (let [snapshot (load-snapshot)]
    (.then snapshot #(dispatch [:connected]))
    (fn [text]
      (.then snapshot (fn [s] (some->> (server/answer s text) (vector :message) dispatch))))))

(defn init
  "Starts the editor's page, connected to the stand-in server."
  []
  (app/start! connect))
