(ns bare-edit.server
  "The HTTP and WebSocket server for one project.
  It serves the editor page and answers requests over one WebSocket, on `127.0.0.1` only.
  A WebSocket is accepted only from the editor's own page and with the startup token, and
  no path in a request may lead outside the project."
  (:require [bare-edit.files :as files]
            [bare-edit.paths :as paths]
            [bare-edit.protocol :as p]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as hk])
  (:import [java.security MessageDigest SecureRandom]))

;; ── Operations ──────────────────────────────────────────────────────────────

(defmulti handle
  "The reply to `req`, a well-formed request, dispatched on its `:op`.
  `ctx` holds the canonical project `:root` and the startup options. An operation without
  a method is answered `:unavailable`."
  (fn [_ctx req] (:op req)))

(defmethod handle :default [_ctx {:keys [id op]}]
  (p/failure id :unavailable (str "Operation " op " is not available yet.")))

(defn- outside
  "The first of `rels` that is not a file path inside the project at `root`, or `nil`."
  [root rels]
  (first (remove #(paths/resolve-in root %) rels)))

(defmethod handle :paths [{:keys [root]} {:keys [id]}]
  (p/reply id :ok {:paths (files/path-list root)}))

(defmethod handle :read [{:keys [root]} {:keys [id] rels :paths}]
  (if-let [bad (outside root rels)]
    (p/failure id :error (str "Not a file in this project: " bad))
    (p/reply id :ok {:files (into {} (map (juxt identity #(files/read-file root %))) rels)})))

(defmethod handle :save [{:keys [root]} {:keys [id path text version]}]
  (if (outside root [path])
    (p/failure id :error (str "Not a file in this project: " path))
    (let [{:keys [conflict] new-version :version} (files/save! root path text version)]
      (if new-version
        (p/reply id :ok {:version new-version})
        (p/failure id :conflict (str path " changed on disk since it was loaded."
                                     (when (= :absent conflict) " It no longer exists.")))))))

(defn respond
  "The reply to `text`, one incoming frame, or `nil` when there is nothing to answer.
  Text that is not EDN, or a message that is not a map with an integer `:id`, has nothing
  to answer. A malformed
  request is answered `:error` with its problems. So is an operation that throws, so no
  request waits forever. `ctx` is passed on to [[handle]]."
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

(defn- static
  "The response for the file `uri` names under `public/` on the classpath, or `nil`.
  `/` serves `index.html`. Responses are never cached, so a rebuilt page always loads."
  [uri]
  (let [path (if (= uri "/") "index.html" (subs uri 1))]
    (when-let [res (and (not (str/includes? path "..")) (io/resource (str "public/" path)))]
      {:status 200
       :headers {"Content-Type" (get content-types (last (str/split path #"\.")) "application/octet-stream")
                 "Cache-Control" "no-store"}
       :body (io/input-stream res)})))

(defn- ws-handler
  "Accepts the WebSocket request `req` when [[allowed?]] by `ctx`, and answers each frame."
  [ctx req]
  (if-not (allowed? ctx req)
    {:status 403 :body "Forbidden"}
    (hk/as-channel req
      {:on-receive (fn [ch text]
                     (when-let [r (respond ctx text)]
                       (hk/send! ch (p/encode r))))})))

(defn- app
  "The Ring handler for the server, which serves the WebSocket and the page's files.
  `ctx` is a promise of the context, delivered once the port is known. A request that
  arrives before then waits for it."
  [ctx]
  (fn [{:keys [uri request-method] :as req}]
    (cond
      (= uri "/ws")              (ws-handler @ctx req)
      (= request-method :get)    (or (static uri) {:status 404 :body "Not found"})
      :else                      {:status 405 :body "Method not allowed"})))

;; ── Lifecycle ───────────────────────────────────────────────────────────────

(defn start!
  "Starts the server for the project at `:root` in `opts`, on `127.0.0.1` and a free port.
  Returns the context as a value: `opts` with the canonical `:root`, and `:port`,
  `:token`, `:url`, and `:stop`, a function that stops the server."
  [{:keys [root] :as opts}]
  (let [ctx    (promise)
        server (hk/run-server (app ctx) {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        port   (hk/server-port server)
        token  (new-token)]
    (deliver ctx (assoc opts :root (str (fs/canonicalize root)) :port port :token token))
    (assoc @ctx
           :url (str "http://127.0.0.1:" port "/?token=" token)
           :stop #(hk/server-stop! server))))
