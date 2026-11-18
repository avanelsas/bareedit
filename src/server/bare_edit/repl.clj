(ns bare-edit.repl
  "The pure side of talking to nREPL.
  It decides which session and port file a file uses, builds the messages sent, and folds
  the stream of messages for one request into one reply. An nREPL message is a map with
  string keys, as bencode gives it."
  (:require [clojure.string :as str]))

(defn session-for
  "The REPL session for the file at `path`, or `nil` for a file that is not evaluated.
  It is `:clj` for `.clj` and `.cljc` files and `:cljs` for `.cljs` files. A `.cljc` file
  goes to Clojure, and its reader conditionals take their `:clj` branch."
  [path]
  (condp re-find (or path "")
    #"\.cljs$"      :cljs
    #"\.cljc?$"     :clj
    nil))

(defn port-files
  "The port files to try, in order, for the session `session`.
  Clojure uses `.nrepl-port`, or the shadow-cljs port when that file is missing, because
  shadow-cljs runs a Clojure session too. ClojureScript uses the shadow-cljs port."
  [session]
  (case session
    :clj  [".nrepl-port" ".shadow-cljs/nrepl.port"]
    :cljs [".shadow-cljs/nrepl.port"]))

(def print-options
  "The nREPL options that pretty-print values at 80 columns.
  nREPL calls the printer with three arguments, so it must be `nrepl.util.print/pprint`."
  {"nrepl.middleware.print/print"   "nrepl.util.print/pprint"
   "nrepl.middleware.print/options" {"right-margin" 80}})

(defn eval-message
  "The nREPL `eval` message for an `:eval` request's `:code`, `:ns`, `:path` and `:line`.
  A file without an `ns` form is evaluated in `user`."
  [{:keys [code ns path line]}]
  (merge {"op" "eval" "code" code "ns" (or ns "user") "file" path "line" line}
         print-options))

(defn load-file-message
  "The nREPL `load-file` message for a `:load-file` request's `:path` and `:text`.
  It sends the buffer's text, so unsaved edits are loaded."
  [{:keys [path text]}]
  (merge {"op" "load-file" "file" text "file-path" path
          "file-name" (last (str/split path #"/"))}
         print-options))

(defn done?
  "Whether the nREPL message `msg` is the last one for its request."
  [msg]
  (boolean (some #{"done"} (get msg "status"))))

(defn- status-of
  "The reply status and message, as `[status message]`, for a stream's `statuses`.
  `ex` is the exception class nREPL reported, if any, and `ns` the namespace the code ran in."
  [statuses ex ns]
  (cond
    (statuses "interrupted")         [:interrupted "The evaluation was interrupted."]
    (statuses "namespace-not-found") [:error (str "The namespace " ns " is not loaded. Evaluate its file first.")]
    (or ex (statuses "eval-error"))  [:error (str "The evaluation threw " (str/replace (or ex "an exception") #"^class " "") ".")]
    :else                            [:ok nil]))

(defn fold-eval
  "The reply, without its `:id`, for `msgs`, the nREPL messages of one `eval` or `load-file`.
  Output pieces are joined in `:out` and `:err`, and `:value` is the last value printed, as
  a REPL shows it. An exception gives `:error` with its class in `:message`; the stack
  trace text, if any, is in `:err`. A request for input is ignored, so an evaluation that
  reads stays running until it is interrupted."
  [msgs]
  (let [statuses (into #{} (mapcat #(get % "status")) msgs)
        ex       (some #(get % "ex") msgs)
        ns       (some #(get % "ns") msgs)
        [status message] (status-of statuses ex ns)]
    (cond-> {:status status
             :out    (apply str (keep #(get % "out") msgs))
             :err    (apply str (keep #(get % "err") msgs))
             :value  (when (= :ok status) (last (keep #(get % "value") msgs)))}
      message (assoc :message message))))

(defn- keywordized
  "The nREPL value `x` with keyword keys in every map, at any depth.
  nREPL sends an empty map as an empty list, so an empty list becomes `{}`."
  [x]
  (cond (map? x)                     (into {} (map (fn [[k v]] [(keyword k) (keywordized v)])) x)
        (and (sequential? x) (empty? x)) {}
        :else                        x))

(defn fold-completions
  "The reply, without its `:id`, for `msgs`, the nREPL messages of one `completions`.
  Each candidate keeps its `:candidate`, `:type` and `:ns`."
  [msgs]
  {:status     :ok
   :candidates (mapv #(select-keys (keywordized %) [:candidate :type :ns])
                     (some #(get % "completions") msgs))})

(defn fold-lookup
  "The reply, without its `:id`, for `msgs`, the nREPL messages of one `lookup`.
  `:info` is nREPL's info map with keyword keys, or `{}` for an unknown symbol."
  [msgs]
  {:status :ok
   :info   (keywordized (or (some #(get % "info") msgs) {}))})

(def queued-message
  "What an interrupt of an evaluation that waits behind another says."
  "That evaluation waits behind one that is still running. Interrupt the running one first.")

(defn interrupt-outcome
  "What one `interrupt` did, from `msgs`, its nREPL messages.
  `:stopped` when it stopped the running evaluation, `:idle` when nothing was running, and
  `:queued` when its evaluation waits behind a running one, which nREPL cannot stop."
  [msgs]
  (let [statuses (into #{} (mapcat #(get % "status")) msgs)]
    (cond (statuses "interrupt-id-mismatch") :queued
          (statuses "session-idle")          :idle
          :else                              :stopped)))

(defn fold-interrupt
  "The reply, without its `:id`, for `msgs`, the nREPL messages of one `interrupt`.
  An interrupt that stopped an evaluation is `:ok` with `warning`, if any. One that found
  the session idle is `:ok`, since the evaluation had ended. One whose evaluation waits
  behind another is `:ok` with a message saying so."
  [msgs warning]
  (case (interrupt-outcome msgs)
    :queued  {:status :ok :message queued-message}
    :idle    {:status :ok}
    :stopped (cond-> {:status :ok} warning (assoc :message warning))))

(defn failure-reply
  "The reply status and message, as `[status message]`, for a failure of the REPL side.
  `:failure` names what happened, and `:port` and `:op` give its details. An evaluation
  that nREPL dropped from its queue is `:interrupted`; every other failure is
  `:unavailable`."
  [{:keys [failure port op]}]
  (case failure
    :dropped     [:interrupted "This evaluation waited behind one that was interrupted, so it did not run."]
    :no-repl     [:unavailable (str "No REPL is running for this project. Start an nREPL in the project folder; "
                                    "BareEditClj reads its port from .nrepl-port or .shadow-cljs/nrepl.port.")]
    :no-answer   [:unavailable (str "No REPL answers on port " port ". Start the project's nREPL, or restart it.")]
    :no-describe [:unavailable "The REPL did not describe itself or give a session."]
    :no-reply    [:unavailable "The REPL did not answer."]
    :no-session  [:unavailable "The REPL refused a new session."]
    :missing-op  [:unavailable (str "This REPL has no " op " operation.")]
    :lost        [:unavailable "The connection to the REPL was lost. The next evaluation connects again."]))

(defn interrupt-warning
  "What to tell the user after an interrupt, or `nil` when it stops the code.
  `:java` is the REPL's Java version and `:attach-flag` its `jdk.attach.allowAttachSelf`
  property. From Java 20, nREPL can stop the evaluation's thread only with that flag;
  without it the session is free again but the code keeps running."
  [{:keys [java attach-flag]}]
  (when (and (some-> java parse-long (>= 20)) (nil? attach-flag))
    (str "The session is free again, but the evaluation keeps running in the background. "
         "Start the REPL's JVM with -Djdk.attach.allowAttachSelf to stop it.")))
