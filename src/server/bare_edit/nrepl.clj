(ns bare-edit.nrepl
  "The connection to the project's nREPL, opened when a request first needs it.
  The server never starts a REPL. It reads the port file and the operations the REPL has.
  Each browser channel gets its own session, so one channel's long evaluation never holds
  up another's. A dropped socket answers its requests in flight, and the next request
  opens a new connection. The one place of the REPL side is the client atom.

  Every request ends in one result: `{:msgs msgs}` with nREPL's messages for it, or
  `{:failure kind}` with a keyword that names what happened, such as `:no-repl`, `:lost`
  or `:dropped`, and its details. [[bare-edit.repl/failure-reply]] turns it into words.

  Connecting and cloning a session do I/O while holding the client's lock, so two requests
  never open two connections or two sessions for one channel. That can hold a request for a
  few seconds, which is fine for a local editor."
  (:require [bare-edit.repl :as repl]
            [babashka.fs :as fs]
            [bencode.core :as b]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io OutputStream PushbackInputStream]
           [java.net InetSocketAddress Socket]))

(def connect-timeout-ms
  "How long to wait for the REPL's port to accept a connection."
  1000)

(def answer-timeout-ms
  "How long to wait for each answer while connecting."
  5000)

(defn client
  "A new client for the project at `root`, not yet connected.
  It is an atom of `{:root :conn :sessions :closing :pending :last-n}`. `:conn` is the
  published connection, `:sessions` maps a browser channel to `{:socket :id}`, `:closing`
  holds the closed channels whose requests are still in flight, and `:pending` maps a
  request number to `{:channel :request-id :op :socket :msgs :on-done}`. The numbers grow
  in the order the requests are sent, and each goes to nREPL as the message id."
  [root]
  (atom {:root root :conn nil :sessions {} :closing #{} :pending {} :last-n 0}))

(defn- ->str
  "The bencode value `x` with every byte array, at any depth, turned into a string."
  [x]
  (cond (bytes? x)      (String. ^bytes x "UTF-8")
        (map? x)        (into {} (map (fn [[k v]] [(->str k) (->str v)])) x)
        (sequential? x) (mapv ->str x)
        :else           x))

(defn- port
  "The port in the first of the port files that exists under `root`, or `nil`."
  [root]
  (some (fn [f]
          (let [file (fs/path root f)]
            (when (fs/exists? file)
              (some-> (slurp (str file)) str/trim parse-long))))
        (repl/port-files :clj)))

(defn- send!
  "Sends `msg` on the connection with the output stream `out`.
  Throws when the socket is closed. The stream is the lock, since it is one per socket."
  [{:keys [^OutputStream out]} msg]
  (locking out
    (b/write-bencode out msg)
    (.flush out)))

(declare start-request!)

(defn- next-n!
  "The next request number of `client`."
  [client]
  (:last-n (swap! client update :last-n inc)))

(defn- closable?
  "Whether the session of `channel` can be closed in the client state `c`.
  That is so when the channel has closed and none of its requests is in flight, since
  closing a session stops its code."
  [c channel]
  (and (contains? (:closing c) channel)
       (not-any? #(= channel (:channel %)) (vals (:pending c)))))

(defn- close-session!
  "Closes and forgets the session of `channel` in `client`, when it is [[closable?]]."
  [client channel]
  (let [[old _] (swap-vals! client (fn [c] (cond-> c (closable? c channel)
                                             (-> (update :closing disj channel)
                                                 (update :sessions dissoc channel)))))
        {:keys [conn]} old
        s (get-in old [:sessions channel])]
    (when (and (closable? old channel) s conn (identical? (:socket s) (:socket conn)))
      (start-request! client conn (next-n! client) {"op" "close" "session" (:id s)} {} (fn [_])))))

(defn- finish!
  "Removes the request `n` from `client` and calls its `on-done` with `result`, once.
  A request of a closed channel calls nothing, and its channel's session is closed when it
  was the last in flight. A failing callback is caught, so it never stops the reader."
  [client n result]
  (let [[old _] (swap-vals! client update :pending dissoc n)]
    (when-let [{:keys [on-done channel]} (get-in old [:pending n])]
      (when-not (contains? (:closing old) channel)
        (try (on-done result)
             (catch Exception _ nil)))
      (when channel (close-session! client channel)))))

(defn- drop!
  "Closes `socket` and answers its requests in flight in `client` with the lost message.
  The client forgets the connection and the sessions on that socket, so the next request
  opens a new connection."
  [client ^Socket socket]
  (try (.close socket) (catch Exception _ nil))
  (let [[old _] (swap-vals! client
                            (fn [c] (-> c
                                        (update :conn #(when-not (identical? socket (:socket %)) %))
                                        (update :sessions #(into {} (remove (fn [[_ s]] (identical? socket (:socket s)))) %)))))]
    (doseq [[n p] (:pending old) :when (identical? socket (:socket p))]
      (finish! client n {:failure :lost}))))

(defn- read-loop!
  "Reads messages from the connection `conn` into the requests of `client`.
  Each message is added to its request, which finishes at its last message. It stops when
  the socket closes, and drops the connection."
  [client {:keys [in socket]}]
  (try
    (loop []
      (let [msg     (->str (b/read-bencode in))
            n       (some-> (get msg "id") parse-long)
            [_ new] (swap-vals! client (fn [c] (cond-> c (get-in c [:pending n])
                                                 (update-in [:pending n :msgs] conj msg))))]
        (when (and (repl/done? msg) (get-in new [:pending n]))
          (finish! client n {:msgs (get-in new [:pending n :msgs])}))
        (recur)))
    (catch Exception _
      (drop! client socket))))

(defn- start-request!
  "Registers `msg` as the request number `n` in `client` and sends it on the connection `conn`.
  `tags` holds the request's `:channel` and `:request-id`, empty for the client's own
  requests, and `on-done` gets its result. When the send fails, the request is answered
  and the connection dropped."
  [client conn n msg tags on-done]
  (swap! client assoc-in [:pending n] (merge tags {:op (get msg "op") :socket (:socket conn)
                                                   :msgs [] :on-done on-done}))
  (try (send! conn (assoc msg "id" (str n)))
       (catch Exception _
         (finish! client n {:failure :lost})
         (drop! client (:socket conn)))))

(defn- ask
  "Sends `msg` on the connection `conn` of `client` and waits for its result.
  It is for the requests made while connecting or cloning a session. A request that times
  out is forgotten."
  [client conn msg]
  (let [p (promise)
        n (next-n! client)]
    (start-request! client conn n msg {} #(deliver p %))
    (let [r (deref p answer-timeout-ms ::timeout)]
      (if (= ::timeout r)
        (do (swap! client update :pending dissoc n)
            {:failure :no-reply})
        r))))

(defn- answer
  "The first value of `key` in the messages of `result`, or `nil`."
  [result key]
  (some #(get % key) (:msgs result)))

(defn- load-printer!
  "Loads the printer of [[bare-edit.repl/print-options]] in the REPL of `client`.
  It evaluates `nil` with those options in `session` on the connection `conn`. nREPL loads
  the printer on its first use, without a lock, so two sessions that both use it first can
  each find it half loaded and fail. Loading it here, while connecting, happens once,
  before any browser session evaluates."
  [client conn session]
  (ask client conn (merge {"op" "eval" "session" session "code" "nil"} repl/print-options)))

(defn- open!
  "Opens a connection for `client` to the REPL at `port`.
  It reads the REPL's operations, and its Java version and attach flag in a session that is
  closed again, after [[load-printer!]] ran in it. The connection is published only when
  complete. Returns `{:conn conn}` or
  a failure."
  [client port]
  (let [socket (Socket.)]
    (try
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) connect-timeout-ms)
      (let [conn {:socket socket
                  :in     (PushbackInputStream. (.getInputStream socket))
                  :out    (.getOutputStream socket)}]
        (doto (Thread. #(read-loop! client conn)) (.setDaemon true) (.start))
        (let [ops    (answer (ask client conn {"op" "describe"}) "ops")
              probe  (answer (ask client conn {"op" "clone"}) "new-session")
              _      (when probe (load-printer! client conn probe))
              props  (when probe
                       (answer (ask client conn {"op" "eval" "session" probe
                                                 "code" (str "[(System/getProperty \"java.specification.version\")"
                                                             " (System/getProperty \"jdk.attach.allowAttachSelf\")]")})
                               "value"))
              [java flag] (try (edn/read-string props) (catch Exception _ nil))]
          (when probe (ask client conn {"op" "close" "session" probe}))
          (if (and (map? ops) probe)
            (let [conn (assoc conn :ops (set (keys ops)) :java java :attach-flag flag)]
              (swap! client assoc :conn conn)
              {:conn conn})
            (do (drop! client socket)
                {:failure :no-describe}))))
      (catch Exception _
        (try (.close socket) (catch Exception _ nil))
        {:failure :no-answer :port port}))))

(defn- connection!
  "The open connection of `client`, opened first when needed, as `{:conn conn}`.
  Returns a failure when there is none."
  [client]
  (locking client
    (if-let [conn (:conn @client)]
      {:conn conn}
      (if-let [p (port (:root @client))]
        (open! client p)
        {:failure :no-repl}))))

(defn- session!
  "The session of the browser `channel` on the connection `conn` of `client`.
  It is cloned on the channel's first request. Returns `{:session id}` or a failure."
  [client conn channel]
  (locking client
    (let [s (get-in @client [:sessions channel])]
      (if (and s (identical? (:socket s) (:socket conn)))
        {:session (:id s)}
        (if-let [id (answer (ask client conn {"op" "clone"}) "new-session")]
          (do (swap! client assoc-in [:sessions channel] {:socket (:socket conn) :id id})
              {:session id})
          {:failure :no-session})))))

(defn request!
  "Sends `msg` to the session of `channel`, a browser connection, in `client`.
  It calls `on-done` with the result: `{:msgs msgs}` when nREPL is done, or a failure. `request-id` is the browser's id for the request, which
  [[interrupt!]] uses. Returns at once; `on-done` runs on another thread, or on this one
  for a failure before sending."
  [client channel request-id msg on-done]
  (let [{:keys [conn] :as c} (connection! client)
        op (get msg "op")]
    (cond
      (:failure c)            (on-done c)
      (not ((:ops conn) op))  (on-done {:failure :missing-op :op op})
      :else (let [{:keys [session] :as s} (session! client conn channel)]
              (if session
                (start-request! client conn (next-n! client) (assoc msg "session" session)
                                {:channel channel :request-id request-id} on-done)
                (on-done s))))))

(defn- answer-dropped!
  "Answers the evaluations of `channel` in `client` that nREPL dropped from its queue.
  Those are the ones still in flight that were sent before the marker, request number
  `marker`, and are not the evaluation `stopped`: the session runs in order, so anything
  sent before the marker that has not ended by the marker's end never ran."
  [client channel stopped marker]
  (doseq [[n p] (:pending @client)
          :when (and (= channel (:channel p)) (not= n stopped) (< n marker)
                     (#{"eval" "load-file"} (:op p)))]
    (finish! client n {:failure :dropped})))

(defn- send-marker!
  "Sends a `nil` evaluation as a marker on `session`, the session of `channel`.
  It follows an interrupt that stopped the evaluation `stopped`, and goes through `client`
  on the connection `conn`. When the marker ends, the evaluations nREPL dropped are
  answered, see [[answer-dropped!]]."
  [client conn channel session stopped]
  (let [marker (next-n! client)]
    (start-request! client conn marker {"op" "eval" "code" "nil" "session" session} {}
                    (fn [_] (answer-dropped! client channel stopped marker)))))

(defn interrupt!
  "Asks the REPL of `client` to stop the evaluation `request-id` of `channel`.
  It calls `on-done` with the interrupt's result, with the REPL's `:java` and
  `:attach-flag` added. When the interrupt stops a running evaluation, nREPL drops the
  evaluations queued behind it, and those are answered as interrupted. Returns `false`,
  calling nothing, when that evaluation is not in flight."
  [client channel request-id on-done]
  (let [{:keys [conn pending sessions]} @client
        [n p]   (first (filter (fn [[_ p]] (and (= channel (:channel p)) (= request-id (:request-id p)))) pending))
        session (get-in sessions [channel :id])]
    (if (and n conn session (identical? (:socket p) (:socket conn)))
      (do (start-request! client conn (next-n! client)
                          {"op" "interrupt" "session" session "interrupt-id" (str n)} {}
                          (fn [result]
                            (when (= :stopped (repl/interrupt-outcome (:msgs result)))
                              (send-marker! client conn channel session n))
                            (on-done (merge result (select-keys conn [:java :attach-flag])))))
          true)
      false)))

(defn forget-channel!
  "Forgets `channel`, a browser connection that closed, in `client`.
  Its requests in flight are not stopped, and their results go nowhere. Its session is
  closed now when none of its requests is in flight, or else when its last one ends, since
  closing a session stops its code. It takes the client's lock, so it waits for a request that is
  connecting or cloning the channel's session."
  [client channel]
  (locking client
    (swap! client update :closing conj channel)
    (close-session! client channel)))

(defn close!
  "Closes the connection of `client`, if one is open, answering its requests in flight."
  [client]
  (when-let [conn (:conn @client)]
    (drop! client (:socket conn))))
