(ns bare-edit.nrepl-test
  "Tests the REPL operations end to end, against a real nREPL.
  The real server is driven over a WebSocket, and the nREPL runs in a temporary project."
  (:require [bare-edit.nrepl :as nrepl]
            [bare-edit.protocol :as p]
            [bare-edit.server :as server]
            [babashka.fs :as fs]
            [babashka.http-client.websocket :as ws]
            [babashka.process :as process]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:dynamic *project*
  "The temporary project folder, where the test nREPL writes its `.nrepl-port`."
  nil)

(defn- start-nrepl!
  "Starts an nREPL for the project at `dir` and waits for its port file.
  The JVM gets `-Djdk.attach.allowAttachSelf`, so an interrupt stops the code. When the
  nREPL does not start, the exception holds its error output."
  [dir]
  (let [err  (fs/file (fs/create-temp-file {:suffix ".txt"}))
        proc (process/process {:dir (str dir) :out :discard :err :write :err-file err}
                              "clojure" "-Sdeps" "{:deps {nrepl/nrepl {:mvn/version \"1.7.0\"}}}"
                              "-J-Djdk.attach.allowAttachSelf" "-M" "-m" "nrepl.cmdline" "--port" "0")]
    (loop [i 0]
      (cond (fs/exists? (fs/path dir ".nrepl-port")) proc
            (< i 240) (do (Thread/sleep 250) (recur (inc i)))
            :else (do (process/destroy-tree proc)
                      (throw (ex-info (str "The test nREPL did not start:\n" (slurp err)) {})))))))

(use-fixtures :once
  (fn [t]
    (let [dir  (fs/create-temp-dir)
          proc (start-nrepl! dir)]
      (try (binding [*project* dir] (t))
           (finally (process/destroy-tree proc))))))

(defn- connect
  "Starts the server on `dir` and opens a WebSocket to it like the editor page.
  Returns `{:srv :ws :inbox}`, where the inbox queues each incoming message as text."
  [dir]
  (let [srv   (server/start! {:root (str dir)})
        inbox (LinkedBlockingQueue.)
        conn  (ws/websocket {:uri (str "ws://127.0.0.1:" (:port srv) "/ws?token=" (:token srv))
                             :headers {"Origin" (str "http://127.0.0.1:" (:port srv))}
                             :on-message (fn [_ws data _last] (.put inbox (str data)))})]
    {:srv srv :ws conn :inbox inbox}))

(defn- send-request
  "Sends the request `msg` on the connection `c`."
  [c msg]
  (ws/send! (:ws c) (p/encode msg)))

(defn- next-reply
  "The next message on the connection `c`, or `nil` after `seconds`."
  [c seconds]
  (some-> (.poll ^LinkedBlockingQueue (:inbox c) seconds TimeUnit/SECONDS) p/decode))

(defn- ask
  "Sends `msg` on the connection `c` and returns its reply, waiting up to 20 seconds."
  [c msg]
  (send-request c msg)
  (next-reply c 20))

(deftest evaluation
  (let [c (connect *project*)]
    (try
      (testing "a form is evaluated, with its output, in one reply"
        (is (= {:id 1 :status :ok :out "hi\n" :err "" :value "3"}
               (ask c (p/request :eval 1 {:path "src/a.clj" :ns nil :line 1
                                           :code "(do (println \"hi\") (+ 1 2))"})))))
      (testing "an exception is an :error naming its class"
        (let [r (ask c (p/request :eval 2 {:path "src/a.clj" :ns nil :line 1 :code "(/ 1 0)"}))]
          (is (= :error (:status r)))
          (is (re-find #"ArithmeticException" (:message r)))))
      (testing "a namespace that is not loaded says to load its file"
        (is (re-find #"not loaded" (:message (ask c (p/request :eval 3 {:path "src/a.clj" :ns "x.y" :line 1 :code "1"}))))))
      (testing "loading a file evaluates the text sent, saved or not"
        (is (= "2" (:value (ask c (p/request :load-file 4 {:path "src/b.clj"
                                                           :text "(ns b) (defn f [] 2) (f)"}))))))
      (testing "the loaded namespace now evaluates"
        (is (= "2" (:value (ask c (p/request :eval 5 {:path "src/b.clj" :ns "b" :line 1 :code "(f)"}))))))
      (testing "completions come from nREPL"
        (let [r (ask c (p/request :completions 6 {:prefix "ma" :ns "user"}))]
          (is (= :ok (:status r)))
          (is (some #(= "map" (:candidate %)) (:candidates r)))))
      (testing "docs come from nREPL lookup"
        (let [r (ask c (p/request :lookup 7 {:path "src/a.clj" :sym "map" :ns "user"}))]
          (is (= "map" (get-in r [:info :name])))
          (is (string? (get-in r [:info :doc])))))
      (testing "ClojureScript has no session yet"
        (is (= :unavailable (:status (ask c (p/request :eval 8 {:path "src/a.cljs" :ns nil :line 1 :code "1"}))))))
      (finally ((:stop (:srv c)))))))

(deftest interrupting
  (let [c (connect *project*)]
    (try
      (send-request c (p/request :eval 1 {:path "src/a.clj" :ns nil :line 1 :code "(loop [i 0] (recur (inc i)))"}))
      (Thread/sleep 500)
      (send-request c (p/request :interrupt 2 {:eval 1}))
      (let [replies (sort-by :id [(next-reply c 20) (next-reply c 20)])]
        (testing "the interrupt is answered :ok, with no warning when the JVM has the flag"
          (is (= {:id 2 :status :ok} (second replies))))
        (testing "the evaluation is answered :interrupted"
          (is (= [1 :interrupted] ((juxt :id :status) (first replies))))))
      (testing "the session works again afterwards"
        (is (= "3" (:value (ask c (p/request :eval 3 {:path "src/a.clj" :ns nil :line 1 :code "(+ 1 2)"}))))))
      (testing "interrupting an evaluation that is done does nothing"
        (is (= {:id 4 :status :ok} (ask c (p/request :interrupt 4 {:eval 3})))))
      (finally ((:stop (:srv c)))))))

(deftest no-repl
  (let [c (connect (fs/create-temp-dir))]
    (try
      (testing "with no port file, an evaluation is :unavailable and says how to start a REPL"
        (let [r (ask c (p/request :eval 1 {:path "src/a.clj" :ns nil :line 1 :code "1"}))]
          (is (= :unavailable (:status r)))
          (is (re-find #"\.nrepl-port" (:message r)))))
      (finally ((:stop (:srv c)))))))

(deftest a-repl-that-restarts
  (let [dir  (fs/create-temp-dir)
        proc (start-nrepl! dir)
        c    (connect dir)]
    (try
      (is (= "1" (:value (ask c (p/request :eval 1 {:path "a.clj" :ns nil :line 1 :code "1"})))))
      (testing "an evaluation in flight when the REPL stops is answered :unavailable"
        (send-request c (p/request :eval 2 {:path "a.clj" :ns nil :line 1 :code "(Thread/sleep 60000)"}))
        (Thread/sleep 300)
        (process/destroy-tree proc)
        @proc
        (is (= [2 :unavailable] ((juxt :id :status) (next-reply c 20)))))
      (testing "with the REPL gone, an evaluation is :unavailable"
        (is (= :unavailable (:status (ask c (p/request :eval 3 {:path "a.clj" :ns nil :line 1 :code "1"}))))))
      (testing "a restarted REPL is found by the next evaluation, with no command"
        (fs/delete-if-exists (fs/path dir ".nrepl-port"))
        (let [proc2 (start-nrepl! dir)]
          (try
            (is (= "2" (:value (ask c (p/request :eval 4 {:path "a.clj" :ns nil :line 1 :code "(+ 1 1)"})))))
            (finally (process/destroy-tree proc2)))))
      (finally ((:stop (:srv c)))
               (process/destroy-tree proc)))))

(deftest each-connection-has-its-own-session
  (let [srv (server/start! {:root (str *project*)})
        tab (fn []
              (let [inbox (LinkedBlockingQueue.)]
                {:srv srv :inbox inbox
                 :ws (ws/websocket {:uri (str "ws://127.0.0.1:" (:port srv) "/ws?token=" (:token srv))
                                    :headers {"Origin" (str "http://127.0.0.1:" (:port srv))}
                                    :on-message (fn [_ws data _last] (.put inbox (str data)))})}))
        a (tab)
        b (tab)]
    (try
      (send-request a (p/request :eval 1 {:path "a.clj" :ns nil :line 1 :code "(loop [i 0] (recur (inc i)))"}))
      (Thread/sleep 300)
      (testing "a long evaluation in one tab does not hold up another tab"
        (is (= {:status :ok :value "3"}
               (select-keys (ask b (p/request :eval 1 {:path "a.clj" :ns nil :line 1 :code "(+ 1 2)"}))
                            [:status :value :message]))))
      (testing "an evaluation that waits behind a running one says so when interrupted"
        (send-request a (p/request :eval 2 {:path "a.clj" :ns nil :line 1 :code "(+ 1 2)"}))
        (Thread/sleep 100)
        (is (re-find #"waits behind" (:message (ask a (p/request :interrupt 3 {:eval 2})))))
        (send-request a (p/request :interrupt 4 {:eval 1}))
        ;; nREPL drops the queued evaluation 2 when it interrupts 1, so the server answers it
        (is (= #{[1 :interrupted] [2 :interrupted] [4 :ok]}
               (set (map (juxt :id :status) [(next-reply a 20) (next-reply a 20) (next-reply a 20)])))))
      (finally ((:stop srv))))))

(deftest a-closed-tab-closes-its-session
  (let [client   (nrepl/client (str *project*))
        sessions (fn [] (let [p (promise)]
                          (nrepl/request! client :watcher 1 {"op" "ls-sessions"} #(deliver p %))
                          (set (some #(get % "sessions") (:msgs (deref p 10000 nil))))))
        result   (promise)]
    (try
      (nrepl/request! client :tab 1 {"op" "eval" "code" "(Thread/sleep 1500)"} #(deliver result %))
      (Thread/sleep 300)
      (let [tab-session (get-in @client [:sessions :tab :id])]
        (nrepl/forget-channel! client :tab)
        (testing "a closed tab's session stays open while its evaluation runs"
          (is (contains? (sessions) tab-session)))
        (Thread/sleep 2500)
        (testing "and is closed when that evaluation ends, with its result going nowhere"
          (is (not (contains? (sessions) tab-session)))
          (is (not (realized? result)))))
      (finally (nrepl/close! client)))))

(deftest closable-sessions
  (let [closable? #'nrepl/closable?
        c {:closing #{:a} :pending {1 {:channel :a :op "eval"} 2 {:channel :b :op "eval"}}}]
    (testing "an open channel's session is never closed"
      (is (not (closable? c :b))))
    (testing "a closed channel's session waits for its requests in flight"
      (is (not (closable? c :a)))
      (is (closable? (update c :pending dissoc 1) :a)))))
