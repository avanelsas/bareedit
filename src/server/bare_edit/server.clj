(ns bare-edit.server
  "The HTTP and WebSocket server for one project.
  It serves the editor page and answers requests over one WebSocket, on `127.0.0.1` only.
  A WebSocket is accepted only from the editor's own page and with the startup token, and
  no path in a request may lead outside the project."
  (:require [bare-edit.config :as config]
            [bare-edit.files :as files]
            [bare-edit.nrepl :as nrepl]
            [bare-edit.paths :as paths]
            [bare-edit.protocol :as p]
            [bare-edit.repl :as repl]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as hk])
  (:import [java.security MessageDigest SecureRandom]))

;; ── Operations ──────────────────────────────────────────────────────────────

(defmulti handle
  "The reply to `req`, a well-formed request, dispatched on its `:op`.
  `ctx` holds the canonical project `:root` and the startup options, with the look and
  feel file at `:config-file`. An operation without a method is answered `:unavailable`."
  (fn [_ctx req] (:op req)))

(defmethod handle :default [_ctx {:keys [id op]}]
  (p/failure id :unavailable (str "Operation " op " is not available yet.")))

(defn- outside
  "The first of `rels` that is not a file path inside the project at `root`, or `nil`."
  [root rels]
  (first (remove #(paths/resolve-in root %) rels)))

(defmethod handle :config [{:keys [config-file]} {:keys [id]}]
  (let [{:keys [config problems]} (config/check (config/read-file config-file))]
    (p/reply id :ok {:config config :problems (mapv config/problem-text problems)})))

(defn- settings-place
  "The folder and the name of the settings file `config-file`, as `[folder name]`."
  [config-file]
  [(str (fs/parent config-file)) (str (fs/file-name config-file))])

(defmethod handle :read-settings [{:keys [config-file]} {:keys [id]}]
  (p/reply id :ok (if (fs/regular-file? config-file)
                    (apply files/read-file (settings-place config-file))
                    {:text "" :version :absent})))

(defmethod handle :save-settings [{:keys [config-file]} {:keys [id text version]}]
  (let [[folder nm] (settings-place config-file)]
    (fs/create-dirs folder)
    (let [{:keys [conflict] new-version :version} (files/save! folder nm text version)]
      (if new-version
        (p/reply id :ok {:version new-version})
        (p/failure id :conflict (str "The settings file changed on disk since it was loaded."
                                     (when (= :absent conflict) " It no longer exists.")))))))

(defmethod handle :rename [{:keys [root]} {:keys [id from to version]}]
  (if-let [bad (outside root [from to])]
    (p/failure id :error (str "Not a file in this project: " bad))
    (let [{:keys [conflict exists] new-version :version} (files/rename! root from to version)]
      (cond
        new-version (p/reply id :ok {:version new-version})
        exists      (p/failure id :error (str to " already exists."))
        :else       (p/failure id :conflict (str from " changed on disk since it was loaded."
                                                 (when (= :absent conflict) " It no longer exists.")))))))

(defmethod handle :project [{:keys [root]} {:keys [id]}]
  (p/reply id :ok {:name (str (fs/file-name root))}))

(defmethod handle :paths [{:keys [root]} {:keys [id]}]
  (p/reply id :ok (select-keys (files/listing root) [:paths :unreadable])))

(defmethod handle :read [{:keys [root]} {:keys [id known] rels :paths}]
  (if-let [bad (outside root rels)]
    (p/failure id :error (str "Not a file in this project: " bad))
    (p/reply id :ok {:files (into {} (for [rel rels]
                                       [rel (p/unless-known (get known rel) (files/read-file root rel))]))})))

(defmethod handle :save [{:keys [root]} {:keys [id path text version]}]
  (if (outside root [path])
    (p/failure id :error (str "Not a file in this project: " path))
    (let [{:keys [conflict] new-version :version} (files/save! root path text version)]
      (if new-version
        (p/reply id :ok {:version new-version})
        (p/failure id :conflict (str path " changed on disk since it was loaded."
                                     (when (= :absent conflict) " It no longer exists.")))))))

;; ── The REPL ───────────────────────────────────────────────────────────────
;; A REPL operation answers later, through the connection's `:reply!`, so its method
;; returns nil. Each request in flight is known by its channel and id, because an id is
;; unique only within one page, and each channel has its own nREPL sessions.

(defn- repl-request!
  "Sends `msg` to the REPL session `kind` for the request `id` from the connection in `ctx`.
  The reply is `(->reply msgs)` when the REPL is done, as `->reply` folds the nREPL
  messages. A failure replies with the status and message of [[repl/failure-reply]].
  Returns `nil`, since the reply comes later."
  [{:keys [repl reply! channel]} kind id msg ->reply]
  (nrepl/request! repl {:channel channel :kind kind :request-id id} msg
                  (fn [{:keys [msgs failure] :as result}]
                    (reply! (if failure
                              (apply p/failure id (repl/failure-reply result))
                              (assoc (->reply msgs) :id id)))))
  nil)

(defn- evaluate
  "Evaluates the request `req` from the connection in `ctx` in the session of its file.
  `->msg` builds the nREPL message from the request and the session kind. A path outside
  the project, or a file that is not Clojure or ClojureScript code, is refused."
  [ctx {:keys [id path] :as req} ->msg]
  (let [kind (p/kind-for path)]
    (cond
      (outside (:root ctx) [path]) (p/failure id :error (str "Not a file in this project: " path))
      (nil? kind)                  (p/failure id :error (str path " is not a Clojure or ClojureScript file."))
      :else                        (repl-request! ctx kind id (->msg req kind) (get-in repl/kinds [kind :fold])))))

(defmethod handle :eval      [ctx req] (evaluate ctx req repl/eval-message))
(defmethod handle :load-file [ctx req] (evaluate ctx req repl/load-file-message))

(defmethod handle :completions [ctx {:keys [id prefix ns]}]
  ;; the browser asks only for .clj and .cljc files; a .cljs file completes from its words
  (repl-request! ctx :clj id {"op" "completions" "prefix" prefix "ns" (or ns "user")} repl/fold-completions))

(defmethod handle :lookup [ctx {:keys [id path sym ns]}]
  (or (when (= :cljs (p/kind-for path))
        (p/failure id :unavailable "Docs are available for Clojure only."))
      (repl-request! ctx :clj id {"op" "lookup" "sym" sym "ns" (or ns "user")} repl/fold-lookup)))

(defmethod handle :interrupt [{:keys [repl reply! channel]} {:keys [id] eval-id :eval}]
  ;; an evaluation that already finished is not in flight, and nothing happens
  (when-not (nrepl/interrupt! repl channel eval-id
                              (fn [{:keys [msgs failure] :as result}]
                                (reply! (if failure
                                          (apply p/failure id (repl/failure-reply result))
                                          (assoc (repl/fold-interrupt msgs (repl/interrupt-warning result))
                                                 :id id)))))
    (p/reply id :ok)))

(defn respond
  "The reply to `text`, one incoming frame, or `nil` when there is nothing to answer now.
  Text that is not EDN, or a message that is not a map with an integer `:id`, has nothing
  to answer. A malformed request is answered `:error` with its problems. So is an operation
  that throws, so no request waits forever. A REPL operation answers later, through
  `:reply!` in `ctx`. `ctx` is passed on to [[handle]]."
  [ctx text]
  (let [msg (p/decode text)]
    (when (and (map? msg) (int? (:id msg)))
      (if-let [problems (p/request-problems msg)]
        (p/failure (:id msg) :error (str/join " " problems))
        (try (handle ctx msg)
             (catch Exception e
               (p/failure (:id msg) :error (str "Operation " (:op msg) " failed: "
                                                (or (ex-message e) (.getName (class e)))))))))))

;; ── Security ───────────────────────────────────────────────────────────────

(defn new-token
  "A new random token of 24 bytes, as hex, which the editor page must present."
  []
  (let [bytes (byte-array 24)]
    (.nextBytes (SecureRandom.) bytes)
    (apply str (map #(format "%02x" %) bytes))))

(defn- same-token?
  "Whether the strings `a` and `b` are equal, compared in constant time."
  [a b]
  (and (string? a) (string? b)
       (MessageDigest/isEqual (.getBytes ^String a "UTF-8") (.getBytes ^String b "UTF-8"))))

(defn- query-param
  "The value of the parameter `k` in the URL query string `query`, or `nil`."
  [query k]
  (some (fn [pair] (let [[pk v] (str/split pair #"=" 2)] (when (= pk k) v)))
        (some-> query (str/split #"&"))))

(defn allowed?
  "Whether the WebSocket request `req` may connect, given the server's `:port` and `:token`.
  It must come from the editor's own page, by its `Origin` header, and carry the startup
  token in its URL."
  [{:keys [port token]} {:keys [headers query-string]}]
  (and (= (get headers "origin") (str "http://127.0.0.1:" port))
       (same-token? token (query-param query-string "token"))))

;; ── HTTP ────────────────────────────────────────────────────────────────────

(def ^:private content-types
  "The content type of each file extension the page uses."
  {"html" "text/html; charset=utf-8" "js" "text/javascript" "css" "text/css"
   "map" "application/json" "svg" "image/svg+xml" "png" "image/png"})

(defn- attribute-text
  "The string `s` escaped to stand as an HTML attribute value between double quotes."
  [s]
  (str/escape s {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn page-with-config
  "The page `html` with the look and feel `config` on its `html` element, as `data-config`.
  The value is `config` as EDN, so the page can show the user's look before it connects."
  [html config]
  (str/replace-first html "<html" (str "<html data-config=\"" (attribute-text (pr-str config)) "\"")))

(defn- static
  "The response for the file `uri` names under `public/` on the classpath, or `nil`.
  Responses are never cached, so a rebuilt page always loads."
  [uri]
  (let [path (subs uri 1)]
    (when-let [res (and (not (str/includes? path "..")) (io/resource (str "public/" path)))]
      {:status 200
       :headers {"Content-Type" (get content-types (last (str/split path #"\.")) "application/octet-stream")
                 "Cache-Control" "no-store"}
       :body (io/input-stream res)})))

(defn- page-response
  "The response for the page, `index.html` with the look and feel of the file `config-file`.
  See [[page-with-config]]. It is never cached, so a changed look loads."
  [config-file]
  {:status 200
   :headers {"Content-Type" (get content-types "html") "Cache-Control" "no-store"}
   :body (page-with-config (slurp (io/resource "public/index.html"))
                           (:config (config/check (config/read-file config-file))))})

;; ── Pages ───────────────────────────────────────────────────────────────────

(def close-wait-ms
  "How long the server waits, after the last page closes, before it stops.
  A reload closes the page's connection and opens a new one, which comes within this time
  and keeps the server running."
  5000)

(defn pages-after
  "The `pages` value after the event `[kind ch]`, where `kind` is `:open` or `:close`.
  `ch` is the page's channel. `pages` is `{:open :closes}`: the set of open channels, and
  how many times the last one closed. That count tells a stop that is still due from one
  a later close replaced."
  [pages [kind ch]]
  (case kind
    :open  (update pages :open conj ch)
    :close (let [pages (update pages :open disj ch)]
             (cond-> pages (empty? (:open pages)) (update :closes inc)))))

(defn- page-closed!
  "Records that the page on the channel `ch` closed, in the `:pages` atom of `ctx`.
  When it was the last, `:stopping` in `ctx` is delivered after `:close-wait-ms`, unless a
  page opened in the meantime."
  [{:keys [pages close-wait-ms stopping]} ch]
  (let [{:keys [open closes]} (swap! pages pages-after [:close ch])]
    (when (empty? open)
      (future
        (Thread/sleep (long close-wait-ms))
        (let [now @pages]
          (when (and (empty? (:open now)) (= closes (:closes now)))
            (deliver stopping :last-page-closed)))))))

(defn- ws-handler
  "Accepts the WebSocket request `req` when [[allowed?]] by `ctx`, and answers each frame.
  Each frame is handled with the channel and a `:reply!` for it in the context. When the
  connection closes, its evaluations in flight are dropped without being stopped, and the
  server stops if it was the last page, see [[page-closed!]]."
  [ctx req]
  (if-not (allowed? ctx req)
    {:status 403 :body "Forbidden"}
    (hk/as-channel req
      {:on-open    (fn [ch] (swap! (:pages ctx) pages-after [:open ch]))
       :on-receive (fn [ch text]
                     (let [reply! #(hk/send! ch (p/encode %))]
                       (some-> (respond (assoc ctx :channel ch :reply! reply!) text) reply!)))
       :on-close   (fn [ch _status]
                     (nrepl/forget-channel! (:repl ctx) ch)
                     (page-closed! ctx ch))})))

(defn- app
  "The Ring handler for the server, which serves the WebSocket and the page's files.
  `ctx` is a promise of the context, delivered once the port is known. A request that
  arrives before then waits for it."
  [ctx]
  (fn [{:keys [uri request-method] :as req}]
    (cond
      (= uri "/ws")              (ws-handler @ctx req)
      (= uri "/")                (page-response (:config-file @ctx))
      (= request-method :get)    (or (static uri) {:status 404 :body "Not found"})
      :else                      {:status 405 :body "Method not allowed"})))

;; ── Lifecycle ───────────────────────────────────────────────────────────────

(defn start!
  "Starts the server for the project at `:root` in `opts`, on `127.0.0.1` and a free port.
  `:build` in `opts` is the shadow-cljs build named with `--build`, if any, and
  `:config-file` the look and feel file, [[config/default-file]] unless given, and
  `:close-wait-ms` the wait after the last page closes, [[close-wait-ms]] unless given.
  Returns the context: `opts` with the canonical `:root`, and `:port`, `:token`, `:repl`,
  the client for the project's nREPLs, `:url`, and `:stop`, a function that stops the
  server and closes the REPL connections. Two entries are places, not values: `:pages`, an
  atom of the open pages that each connection changes as it opens and closes, and
  `:stopping`, a promise delivered once the last page has closed and none opened in the
  wait."
  [{:keys [root] :as opts}]
  (let [ctx    (promise)
        server (hk/run-server (app ctx) {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        port   (hk/server-port server)
        token  (new-token)
        root   (str (fs/canonicalize root))
        client (nrepl/client root (:build opts))]
    (deliver ctx (assoc opts :root root :port port :token token :repl client
                            :config-file (or (:config-file opts) config/default-file)
                            :close-wait-ms (or (:close-wait-ms opts) close-wait-ms)
                            :pages (atom {:open #{} :closes 0})
                            :stopping (promise)))
    (assoc @ctx
           :url (str "http://127.0.0.1:" port "/?token=" token)
           :stop #(do (hk/server-stop! server) (nrepl/close! client)))))
