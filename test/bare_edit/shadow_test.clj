(ns bare-edit.shadow-test
  "Tests ClojureScript evaluation end to end, against a real shadow-cljs watch and node.
  The fixture project is copied to a temporary folder, next to this repository's
  `node_modules`, and the real server is driven over a WebSocket. shadow-cljs runs as a
  server, and the test starts and stops the watch through the editor, because the
  `shadow-cljs watch` command exits when its watch stops."
  (:require [bare-edit.protocol :as p]
            [bare-edit.server :as server]
            [bare-edit.ws-client :as ws-client :refer [ask next-reply send-request]]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *project*
  "The temporary copy of the fixture project, where shadow-cljs runs its watch."
  nil)

(def ^:dynamic *log*
  "The file that holds the output of shadow-cljs, shown when something does not happen."
  nil)

(defn- wait-until
  "Waits until `done?` returns true, checking every 250 ms, and throws after two minutes.
  `what` names what is awaited, for the exception, which also holds the text of `log`, a
  file, when given."
  ([done? what] (wait-until done? what nil))
  ([done? what log]
   (loop [i 0]
     (cond (done?)   true
           (< i 480) (do (Thread/sleep 250) (recur (inc i)))
           :else     (throw (ex-info (str what " did not happen." (some->> log slurp (str "\n"))) {}))))))

(use-fixtures :once
  (fn [t]
    (let [dir  (fs/create-temp-dir)
          log  (fs/file (fs/create-temp-file {:suffix ".txt"}))
          _    (fs/copy-tree "test/fixtures/shadow_project" dir)
          _    (fs/create-sym-link (fs/path dir "node_modules") (fs/absolutize "node_modules"))
          proc (process/process {:dir (str dir) :out :write :out-file log :err :write :err-file log}
                                (str (fs/absolutize "node_modules/.bin/shadow-cljs")) "server")]
      (try
        (wait-until #(fs/exists? (fs/path dir ".shadow-cljs" "nrepl.port")) "The shadow-cljs nREPL" log)
        (binding [*project* dir *log* log] (t))
        (finally (process/destroy-tree proc))))))

(defn- eval-request
  "An `:eval` request with `id` for `code` in the file `path` and the namespace `ns`."
  ([id code] (eval-request id code "src/demo/core.cljs" "demo.core"))
  ([id code path ns]
   (p/request :eval id {:path path :ns ns :line 1 :code code})))

(defn- start-runtime!
  "Starts node on the build's output, as the JS runtime, and returns its process."
  []
  (process/process {:dir (str *project*) :out :discard :err :discard} "node" "out/app.js"))

(defn- session-count
  "How many sessions the shadow-cljs nREPL holds, asked through `c` with the request `id`."
  [c id]
  (parse-long (:value (ask c (eval-request id "(count @@#'nrepl.middleware.session/sessions)"
                                           "src/demo/x.clj" nil)))))

(deftest clojurescript-evaluation
  (let [c    (ws-client/connect (server/start! {:root (str *project*)}))
        ids  (atom 0)
        id!  #(swap! ids inc)
        sessions-before (atom nil)]
    (try
      (testing "with no watch, an evaluation is :no-runtime"
        (let [r (ask c (eval-request (id!) "(+ 1 2)"))]
          (is (= [:no-runtime "No shadow-cljs watch is running. Start one, such as npx shadow-cljs watch app."]
                 ((juxt :status :message) r))
              (pr-str r))))
      ;; the watch starts through the Clojure session of the same shadow-cljs
      (ask c (eval-request (id!) "(shadow.cljs.devtools.api/watch :app)" "src/demo/x.clj" nil) 120)
      (wait-until #(fs/exists? (fs/path *project* "out" "app.js")) "The first build" *log*)
      (testing "with a watch but no JS runtime, an evaluation is :no-runtime"
        (let [r (ask c (eval-request (id!) "(+ 1 2)"))]
          (is (= :no-runtime (:status r)) (pr-str r))))
      (let [node (start-runtime!)]
        (try
          (testing "once node runs the build, a form evaluates in its file's namespace"
            (wait-until #(= :ok (:status (ask c (eval-request (id!) "(+ 1 2)")))) "The JS runtime")
            (let [r (ask c (eval-request (id!) "(f)"))]
              (is (= "42" (:value r)) (pr-str r))))
          (testing "a thrown error is an :error naming its class"
            (let [r (ask c (eval-request (id!) "(throw (js/Error. \"boom\"))"))]
              (is (= [:error "The evaluation threw Error."] ((juxt :status :message) r)) (pr-str r))))
          (testing "evaluating a file loads the text sent, not the file on disk"
            (let [r (ask c (p/request :load-file (id!) {:path "src/demo/core.cljs"
                                                         :text "(ns demo.core)\n(defn f [] 43)\n(f)"}))]
              (is (= "43" (:value r)) (pr-str r))))
          (testing "a ClojureScript evaluation cannot be interrupted, and still ends"
            (let [slow (id!)]
              (send-request c (eval-request slow "(let [t (js/Date.now)] (loop [] (when (< (- (js/Date.now) t) 2000) (recur))) :done)"))
              (Thread/sleep 300)
              (let [replies (sort-by :id [(ask c (p/request :interrupt (id!) {:eval slow})) (next-reply c 20)])]
                (is (= [[slow :ok ":done"] [(inc slow) :unavailable nil]]
                       (map (juxt :id :status :value) replies))
                    (pr-str replies)))))
          (testing "Clojure evaluates in the same shadow-cljs, from its port file"
            (let [r (ask c (eval-request (id!) "(+ 1 2)" "src/demo/x.clj" nil))]
              (is (= "3" (:value r)) (pr-str r))))
          (testing "a watch that stops is :no-runtime, and ClojureScript never runs as Clojure"
            (ask c (eval-request (id!) "(shadow.cljs.devtools.api/stop-worker :app)" "src/demo/x.clj" nil))
            (reset! sessions-before (session-count c (id!)))
            (let [r (ask c (eval-request (id!) "(+ 1 2)"))]
              (is (= :no-runtime (:status r)) (pr-str r))
              (is (re-find #"watch stopped" (str (:message r))) (pr-str r)))
            (let [r (ask c (eval-request (id!) "(+ 1 2)"))]
              (is (= :no-runtime (:status r)) (pr-str r))
              (is (re-find #"No shadow-cljs watch" (str (:message r))) (pr-str r)))
            (testing "and the session shadow turned back into Clojure is closed"
              (Thread/sleep 500)
              (is (= (dec @sessions-before) (session-count c (id!))))))
          (finally (process/destroy-tree node))))
      (finally ((:stop (:srv c)))))))
