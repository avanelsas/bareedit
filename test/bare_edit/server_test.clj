(ns bare-edit.server-test
  "Tests the real server over HTTP and a WebSocket, driven as the browser would."
  (:require [bare-edit.protocol :as p]
            [bare-edit.server :as server]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.http-client.websocket :as ws]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *srv*
  "The server the current test runs against, as [[bare-edit.server/start!]] returns it."
  nil)

(use-fixtures :each
  (fn [t]
    (let [srv (server/start! {:root (str (fs/create-temp-dir))})]
      (try (binding [*srv* srv] (t))
           (finally ((:stop srv)))))))

(defn- connect
  "Opens a WebSocket like the editor page does, to the server in the map `srv`.
  The map gives `:port` and `:token`, and optionally an `:origin` to send instead of the
  page's. Returns `{:ws :inbox}`, where the inbox queues each incoming message as text."
  [{:keys [port token origin] :as _srv}]
  (let [inbox (java.util.concurrent.LinkedBlockingQueue.)
        conn  (ws/websocket {:uri (str "ws://127.0.0.1:" port "/ws?token=" token)
                             :headers {"Origin" (or origin (str "http://127.0.0.1:" port))}
                             :on-message (fn [_ws data _last] (.put inbox (str data)))})]
    {:ws conn :inbox inbox}))

(defn- ask
  "Sends `msg`, a message map or raw text, on the connection `conn` and returns the reply.
  Returns `nil` when no message arrives within 3 seconds."
  [{:keys [ws inbox] :as _conn} msg]
  (ws/send! ws (if (string? msg) msg (p/encode msg)))
  (some-> (.poll inbox 3 java.util.concurrent.TimeUnit/SECONDS) p/decode))

(deftest page-is-served
  (let [resp (http/get (str "http://127.0.0.1:" (:port *srv*) "/") {:throw false})]
    (is (= 200 (:status resp)))
    (is (re-find #"BareEditClj" (:body resp)))))

(deftest only-on-loopback
  (is (re-find #"^http://127\.0\.0\.1:\d+/\?token=[0-9a-f]{48}$" (:url *srv*))))

(deftest requests-get-replies
  (let [c (connect *srv*)]
    (testing "a known operation that is not built yet"
      (is (= {:id 1 :status :unavailable :message "Operation :config is not available yet."}
             (ask c (p/request :config 1 {})))))
    (testing "a malformed request with an id gets an :error reply"
      (let [r (ask c {:op :save :id 2 :path "a.clj"})]
        (is (= :error (:status r)))
        (is (nil? (p/reply-problems :save r)))))
    (testing "text that is not EDN gets no reply, and the connection stays usable"
      (is (nil? (ask c "{:op")))
      (is (= 3 (:id (ask c (p/request :config 3 {}))))))
    (testing "files: list, save a new file, read it back, and a stale save conflicts"
      (is (= {:id 4 :status :ok :paths []} (ask c (p/request :paths 4 {}))))
      (let [{v :version :as saved} (ask c (p/request :save 5 {:path "src/a.clj" :text "(ns a)" :version :absent}))]
        (is (= :ok (:status saved)))
        (is (= {"src/a.clj" {:text "(ns a)" :version v}}
               (:files (ask c (p/request :read 6 {:paths ["src/a.clj"]})))))
        (is (= :conflict (:status (ask c (p/request :save 7 {:path "src/a.clj" :text "x" :version :absent})))))))
    (testing "paths that leave the project are refused"
      (is (= :error (:status (ask c (p/request :read 8 {:paths ["../etc/passwd"]})))))
      (is (= :error (:status (ask c (p/request :save 9 {:path "/tmp/x" :text "" :version :absent}))))))
    (testing "an operation that fails still gets an :error reply"
      (ask c (p/request :save 10 {:path "d/x.clj" :text "" :version :absent}))
      (let [r (ask c (p/request :save 11 {:path "d" :text "" :version :absent}))]
        (is (= [11 :error] [(:id r) (:status r)]))
        (is (re-find #"^Operation :save failed: " (:message r)))))))

(deftest refused-connections
  (testing "wrong token"
    (is (thrown? Exception (connect (assoc *srv* :token "nope")))))
  (testing "wrong origin, such as localhost, which is a different origin"
    (is (thrown? Exception (connect (assoc *srv* :origin (str "http://localhost:" (:port *srv*))))))))
