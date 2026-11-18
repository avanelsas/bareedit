(ns bare-edit.repl
  "The pure side of talking to nREPL.
  It decides which port file a session kind uses, builds the messages sent, and folds
  the stream of messages for one request into one reply. An nREPL message is a map with
  string keys, as bencode gives it. A ClojureScript session runs in shadow-cljs, whose
  replies are read from its own signals, see [[fold-cljs-eval]]."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def print-options
  "The nREPL options that pretty-print values at 80 columns.
  nREPL calls the printer with three arguments, so it must be `nrepl.util.print/pprint`."
  {"nrepl.middleware.print/print"   "nrepl.util.print/pprint"
   "nrepl.middleware.print/options" {"right-margin" 80}})

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

(def ^:private no-runtime-message
  "What a ClojureScript evaluation says when no JS runtime is connected to its build."
  (str "No JS runtime is connected to the shadow-cljs build. "
       "Open the app in a browser, or start it, and evaluate again."))

(def ^:private worker-exited-message
  "What a ClojureScript evaluation says when the build's watch stopped."
  "The shadow-cljs watch stopped. Start it again, and the next evaluation reconnects.")

(def shadow-signals
  "The texts in shadow-cljs's replies that say what happened, by what they mean.
  shadow-cljs reports trouble as text, not as data, so the ClojureScript side depends on
  these. They are the texts of shadow-cljs 3.5, pinned by the recorded streams in the
  tests: a version that changes one fails a test.

  - `:no-runtime`: error output when no JS runtime is connected to the build.
  - `:worker-exited`: error output when the build's watch stopped, after which the session
    is a Clojure session again.
  - `:exception`: the value of an evaluation that threw.
  - `:exception-class`: the error output that names the class of what was thrown.
  - `:switched`: the start of the value when a session switched to a build."
  {:no-runtime      "No available JS runtime"
   :worker-exited   "The worker for this REPL has exited"
   :exception       ":repl/exception!"
   :exception-class #"Execution error \((\S+)\)"
   :switched        "[:selected "})

(defn worker-exited?
  "Whether `msgs`, the replies of a ClojureScript evaluation, say that the build's watch stopped.
  shadow-cljs then turns the session back into Clojure, so it must not be used again."
  [msgs]
  (boolean (some #(some-> (get % "err") (str/includes? (:worker-exited shadow-signals))) msgs)))

(defn fold-cljs-eval
  "The reply, without its `:id`, for `msgs`, the shadow-cljs messages of one ClojureScript `eval`.
  shadow-cljs reports trouble only as text, so the reply is read from [[shadow-signals]].
  No JS runtime, or a stopped watch, is `:no-runtime`. An evaluation that threw is an
  `:error` naming the class, and error output with no value is an `:error` too, such as a
  form that does not read. Warnings that come with a value
  stay in `:err`, and the status is `:ok`."
  [msgs]
  (let [out    (apply str (keep #(get % "out") msgs))
        err    (apply str (keep #(get % "err") msgs))
        values (keep #(get % "value") msgs)
        reply  (fn [status value message]
                 (cond-> {:status status :out out :err err :value value}
                   message (assoc :message message)))]
    (cond
      (str/includes? err (:no-runtime shadow-signals)) (reply :no-runtime nil no-runtime-message)
      (worker-exited? msgs)                         (reply :no-runtime nil worker-exited-message)
      (some #{(:exception shadow-signals)} values)
      (reply :error nil (str "The evaluation threw "
                             (or (second (re-find (:exception-class shadow-signals) err)) "an exception") "."))
      (and (empty? values) (seq err))               (reply :error nil "The code could not be read or compiled.")
      :else                                         (reply :ok (last values) nil))))

(def kinds
  "What each session kind is, by kind, `:clj` or `:cljs`.

  - `:port-files`: the port files to try, in order. Clojure uses `.nrepl-port`, or the
    shadow-cljs port when that file is missing, because shadow-cljs runs a Clojure session
    too. ClojureScript uses the shadow-cljs port.
  - `:no-port`: the failure when none of those files exists.
  - `:default-ns`: the namespace of a file without an `ns` form.
  - `:fold`: the function that folds the messages of an evaluation into its reply.
  - `:build?`: whether a new session is switched to a shadow-cljs build.
  - `:no-interrupt`: the failure when an evaluation is asked to stop, or `nil` when it can
    be stopped. shadow-cljs cannot stop running JavaScript.
  - `:ended?`: whether the messages of a request say the session can no longer be used."
  {:clj  {:port-files     [".nrepl-port" ".shadow-cljs/nrepl.port"]
          :no-port        :no-repl
          :default-ns     "user"
          :fold           fold-eval
          :build?         false
          :no-interrupt   nil
          :ended?         (constantly false)}
   :cljs {:port-files     [".shadow-cljs/nrepl.port"]
          :no-port        :no-shadow
          :default-ns     "cljs.user"
          :fold           fold-cljs-eval
          :build?         true
          :no-interrupt   :cljs-interrupt
          :ended?         worker-exited?}})

(defn eval-message
  "The nREPL `eval` message for an `:eval` request to a session of `kind`.
  The request gives the `:code`, `:ns`, `:path` and `:line`. A file without an `ns` form is
  evaluated in the `:default-ns` of its kind in [[kinds]]."
  [{:keys [code ns path line]} kind]
  (merge {"op" "eval" "code" code "ns" (or ns (get-in kinds [kind :default-ns])) "file" path "line" line}
         print-options))

(defn load-file-message
  "The nREPL message that loads a `:load-file` request's `:path` and `:text` in a `kind` session.
  It sends the buffer's text, so unsaved edits are loaded. Clojure gets nREPL's
  `load-file`. ClojureScript gets an `eval` of the whole text, because shadow-cljs's
  `load-file` reads the file from disk and ignores the text."
  [{:keys [path text]} kind]
  (case kind
    :clj  (merge {"op" "load-file" "file" text "file-path" path
                  "file-name" (last (str/split path #"/"))}
                 print-options)
    :cljs (eval-message {:code text :ns nil :path path :line 1} :cljs)))

(def active-builds-code
  "The code that lists the builds whose shadow-cljs watch is running, as a set of keywords."
  "(shadow.cljs.devtools.api/active-builds)")

(defn switch-code
  "The code that switches a session to the ClojureScript REPL of `build`, a keyword."
  [build]
  (str "(shadow.cljs.devtools.api/repl " build ")"))

(defn read-builds
  "The set of builds in `value`, the printed answer to [[active-builds-code]], or `#{}`.
  Anything that is not a set of keywords, such as no answer at all, means no build runs."
  [value]
  (let [v (try (edn/read-string (str value)) (catch Exception _ nil))]
    (if (and (set? v) (every? keyword? v)) v #{})))

(defn choose-build
  "The build to evaluate ClojureScript in, from `active`, the builds whose watch runs.
  `named` is the build given with `--build`, or `nil`. Returns `{:build b}`, or a failure:
  `:no-watch` when none runs, `:build-not-running` when the named one does not, and
  `:several-builds` when several run and none is named."
  [active named]
  (cond
    named               (if (active named) {:build named} {:failure :build-not-running :build named})
    (empty? active)     {:failure :no-watch}
    (= 1 (count active)) {:build (first active)}
    :else               {:failure :several-builds :builds (vec (sort active))}))

(defn switched?
  "Whether `msgs`, the replies to [[switch-code]], say the session is now ClojureScript."
  [msgs]
  (boolean (some #(some-> (get % "value") (str/starts-with? (:switched shadow-signals))) msgs)))

(defn failure-reply
  "The reply status and message, as `[status message]`, for a failure of the REPL side.
  `:failure` names what happened, and `:port`, `:op`, `:build` and `:builds` give its
  details. An evaluation that nREPL dropped from its queue is `:interrupted`. A
  ClojureScript build without a running watch is `:no-runtime`. Every other failure is
  `:unavailable`."
  [{:keys [failure port op build builds]}]
  (case failure
    :dropped     [:interrupted "This evaluation waited behind one that was interrupted, so it did not run."]
    :no-repl     [:unavailable (str "No REPL is running for this project. Start an nREPL in the project folder; "
                                    "BareEditClj reads its port from .nrepl-port or .shadow-cljs/nrepl.port.")]
    :no-answer   [:unavailable (str "No REPL answers on port " port ". Start the project's nREPL, or restart it.")]
    :no-describe [:unavailable "The REPL did not describe itself or give a session."]
    :no-reply    [:unavailable "The REPL did not answer."]
    :no-session  [:unavailable "The REPL refused a new session."]
    :missing-op  [:unavailable (str "This REPL has no " op " operation.")]
    :lost        [:unavailable "The connection to the REPL was lost. The next evaluation connects again."]
    :no-shadow   [:unavailable (str "No shadow-cljs is running for this project. Start a watch in the project folder, "
                                    "such as npx shadow-cljs watch app.")]
    :no-watch    [:no-runtime "No shadow-cljs watch is running. Start one, such as npx shadow-cljs watch app."]
    :build-not-running [:no-runtime (str "The build " build " named with --build has no running watch.")]
    :several-builds    [:unavailable (str "Several shadow-cljs builds run: " (str/join ", " builds) ". "
                                          "Restart bare-edit with --build and one of them.")]
    :not-switched      [:unavailable "shadow-cljs did not switch the session to ClojureScript."]
    :cljs-interrupt    [:unavailable "A ClojureScript evaluation cannot be interrupted: shadow-cljs cannot stop running JavaScript."]))

(defn interrupt-warning
  "What to tell the user after an interrupt, or `nil` when it stops the code.
  `:java` is the REPL's Java version and `:attach-flag` its `jdk.attach.allowAttachSelf`
  property. From Java 20, nREPL can stop the evaluation's thread only with that flag;
  without it the session is free again but the code keeps running."
  [{:keys [java attach-flag]}]
  (when (and (some-> java parse-long (>= 20)) (nil? attach-flag))
    (str "The session is free again, but the evaluation keeps running in the background. "
         "Start the REPL's JVM with -Djdk.attach.allowAttachSelf to stop it.")))
