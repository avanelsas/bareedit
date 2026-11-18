(ns bare-edit.ws-client
  "A WebSocket client for tests, which talks to a running server like the editor page."
  (:require [bare-edit.protocol :as p]
            [babashka.http-client.websocket :as ws])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn connect
  "Opens a WebSocket to the started server `srv` like the editor page.
  Returns `{:srv :ws :inbox}`, where the inbox queues each incoming message as text."
  [srv]
  (let [inbox (LinkedBlockingQueue.)
        conn  (ws/websocket {:uri (str "ws://127.0.0.1:" (:port srv) "/ws?token=" (:token srv))
                             :headers {"Origin" (str "http://127.0.0.1:" (:port srv))}
                             :on-message (fn [_ws data _last] (.put inbox (str data)))})]
    {:srv srv :ws conn :inbox inbox}))

(defn send-request
  "Sends the request `msg` on the connection `c`."
  [c msg]
  (ws/send! (:ws c) (p/encode msg)))

(defn next-reply
  "The next message on the connection `c`, or `nil` after `seconds`."
  [c seconds]
  (some-> (.poll ^LinkedBlockingQueue (:inbox c) seconds TimeUnit/SECONDS) p/decode))

(defn ask
  "Sends `msg` on the connection `c` and returns its reply, waiting up to `seconds`, or 20."
  ([c msg] (ask c msg 20))
  ([c msg seconds]
   (send-request c msg)
   (next-reply c seconds)))
