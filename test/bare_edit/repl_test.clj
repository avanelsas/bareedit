(ns bare-edit.repl-test
  "Tests the pure side of the REPL against nREPL streams recorded from a real nREPL.
  It covers the fold into replies, the messages, the sessions and the port files."
  (:require [bare-edit.protocol :as p]
            [bare-edit.repl :as repl]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is are testing]]))

(def streams
  "The recorded nREPL message streams, by case."
  (edn/read-string (slurp (io/resource "bare_edit/nrepl_streams.edn"))))

(defn- fold
  "The reply for the recorded stream `k`."
  [k]
  (repl/fold-eval (streams k)))

(deftest folding-evaluations
  (are [k reply] (= reply (fold k))
    :value      {:status :ok :out "" :err "" :value "3"}
    :out-value  {:status :ok :out "computing\n" :err "warn\n" :value "42"}
    :many-forms {:status :ok :out "" :err "" :value "3"}
    :load-file  {:status :ok :out "" :err "" :value "1"})
  (testing "a pretty-printed value is kept as printed"
    (is (re-find #"\n  :prefs\n" (:value (fold :pretty)))))
  (testing "an exception is an :error naming its class, with nREPL's text in :err"
    (is (= {:status :error :out "" :value nil
            :err "Execution error (ArithmeticException) at user/eval2512 (REPL:1).\nDivide by zero\n"
            :message "The evaluation threw java.lang.ArithmeticException."}
           (fold :error))))
  (testing "a namespace that is not loaded says how to load it"
    (is (= :error (:status (fold :no-ns))))
    (is (= "The namespace not.loaded is not loaded. Evaluate its file first." (:message (fold :no-ns)))))
  (testing "an interrupted evaluation"
    (is (= {:status :interrupted :out "" :err "" :value nil :message "The evaluation was interrupted."}
           (fold :interrupted))))
  (testing "every folded reply is a well-formed :eval reply"
    (doseq [k (keys streams)]
      (is (nil? (p/reply-problems :eval (assoc (fold k) :id 1))) (str k)))))

(deftest done?
  (is (repl/done? {"id" "1" "status" ["done"]}))
  (is (repl/done? {"id" "4" "status" ["namespace-not-found" "done" "error"]}))
  (is (not (repl/done? {"id" "1" "value" "3"}))))

(deftest kinds
  (are [path kind] (= kind (repl/kind-for path))
    "src/a.clj"   :clj
    "src/a.cljc"  :clj
    "src/a.cljs"  :cljs
    "deps.edn"    nil
    "README.md"   nil))

(deftest port-files
  (is (= [".nrepl-port" ".shadow-cljs/nrepl.port"] (get-in repl/kinds [:clj :port-files])))
  (is (= [".shadow-cljs/nrepl.port"] (get-in repl/kinds [:cljs :port-files]))))

(deftest eval-message
  (is (= {"op" "eval" "code" "(+ 1 2)" "ns" "a.core" "file" "src/a/core.clj" "line" 12
          "nrepl.middleware.print/print" "nrepl.util.print/pprint"
          "nrepl.middleware.print/options" {"right-margin" 80}}
         (repl/eval-message {:code "(+ 1 2)" :ns "a.core" :path "src/a/core.clj" :line 12} :clj)))
  (testing "a file without an ns form is evaluated in the session's default namespace"
    (is (= "user" (get (repl/eval-message {:code "1" :ns nil :path "a.clj" :line 1} :clj) "ns")))
    (is (= "cljs.user" (get (repl/eval-message {:code "1" :ns nil :path "a.cljs" :line 1} :cljs) "ns")))))

(deftest load-file-messages
  (testing "Clojure loads the text with nREPL's load-file"
    (is (= {"op" "load-file" "file" "(ns a)" "file-path" "src/a.clj" "file-name" "a.clj"}
           (select-keys (repl/load-file-message {:path "src/a.clj" :text "(ns a)"} :clj)
                        ["op" "file" "file-path" "file-name"]))))
  (testing "ClojureScript evaluates the whole text, since shadow-cljs's load-file reads the disk"
    (is (= {"op" "eval" "code" "(ns a)" "ns" "cljs.user" "file" "src/a.cljs" "line" 1}
           (select-keys (repl/load-file-message {:path "src/a.cljs" :text "(ns a)"} :cljs)
                        ["op" "code" "ns" "file" "line"])))))

(deftest interrupt-warning
  (are [java flag warning?] (= warning? (some? (repl/interrupt-warning {:java java :attach-flag flag})))
    "21" nil    true
    "21" "true" false
    "17" nil    false))

(deftest folding-completions-and-docs
  (testing "completions keep the candidate, its type and its namespace"
    (is (= {:status :ok
            :candidates [{:candidate "reduce" :type "function" :ns "clojure.core"}
                         {:candidate "reduce-kv" :type "function" :ns "clojure.core"}
                         {:candidate "reduced" :type "function" :ns "clojure.core"}
                         {:candidate "reduced?" :type "function" :ns "clojure.core"}
                         {:candidate "reductions" :type "function" :ns "clojure.core"}]}
           (repl/fold-completions (streams :completions)))))
  (testing "docs are nREPL's info map with keyword keys"
    (let [{:keys [status info]} (repl/fold-lookup (streams :lookup))]
      (is (= :ok status))
      (is (= ["reduce" "clojure.core" "([f coll] [f val coll])"] ((juxt :name :ns :arglists-str) info)))))
  (testing "an unknown symbol has no info, which nREPL sends as an empty list"
    (is (= {:status :ok :info {}} (repl/fold-lookup (streams :lookup-unknown))))))

(deftest folding-interrupts
  (are [k warning want] (= want (repl/fold-interrupt (streams k) warning))
    :interrupt-interrupted nil       {:status :ok}
    :interrupt-interrupted "careful" {:status :ok :message "careful"}
    :interrupt-idle        "careful" {:status :ok}
    :interrupt-mismatch    nil       {:status :ok
                                      :message (str "That evaluation waits behind one that is still running. "
                                                    "Interrupt the running one first.")}))

(deftest interrupt-outcomes
  (are [k outcome] (= outcome (repl/interrupt-outcome (streams k)))
    :interrupt-interrupted :stopped
    :interrupt-idle        :idle
    :interrupt-mismatch    :queued))

(deftest failure-replies
  (testing "an evaluation nREPL dropped is :interrupted"
    (is (= :interrupted (first (repl/failure-reply {:failure :dropped})))))
  (testing "every other failure is :unavailable, with its details in the message"
    (is (= [:unavailable "No REPL answers on port 7888. Start the project's nREPL, or restart it."]
           (repl/failure-reply {:failure :no-answer :port 7888})))
    (is (= [:unavailable "This REPL has no lookup operation."]
           (repl/failure-reply {:failure :missing-op :op "lookup"})))
    (doseq [k [:no-repl :no-describe :no-reply :no-session :lost]]
      (is (= :unavailable (first (repl/failure-reply {:failure k}))) (str k)))))

(def shadow-streams
  "The nREPL message streams recorded from shadow-cljs, by case."
  (edn/read-string (slurp (io/resource "bare_edit/shadow_streams.edn"))))

(defn- fold-cljs
  "The ClojureScript reply for the recorded shadow-cljs stream `k`."
  [k]
  (repl/fold-cljs-eval (shadow-streams k)))

(deftest folding-clojurescript-evaluations
  (are [k reply] (= reply (fold-cljs k))
    :value        {:status :ok :out "" :err "" :value "3"}
    :out-value    {:status :ok :out "hi\n" :err "" :value "42"}
    :many-forms   {:status :ok :out "" :err "" :value "3"}
    :file-as-eval {:status :ok :out "" :err "" :value ":g3"}
    :with-state   {:status :ok :out "" :err "" :value "3"})
  (testing "a thrown exception is an :error naming its class, with shadow's text in :err"
    (is (= [:error "The evaluation threw Error." nil] ((juxt :status :message :value) (fold-cljs :js-error))))
    (is (= "The evaluation threw ExceptionInfo." (:message (fold-cljs :ex-info))))
    (is (re-find #"boom" (:err (fold-cljs :js-error)))))
  (testing "a form that does not read is an :error"
    (is (= [:error "The code could not be read or compiled."] ((juxt :status :message) (fold-cljs :read-error)))))
  (testing "a warning that comes with a value is :ok, and the warning stays in :err"
    (is (= [:ok "nil"] ((juxt :status :value) (fold-cljs :undeclared))))
    (is (re-find #"undeclared Var /nope" (:err (fold-cljs :undeclared)))))
  (testing "no JS runtime, or a stopped watch, is :no-runtime"
    (is (= :no-runtime (:status (fold-cljs :no-runtime))))
    (is (re-find #"No JS runtime" (:message (fold-cljs :no-runtime))))
    (is (= :no-runtime (:status (fold-cljs :worker-exited))))
    (is (re-find #"watch stopped" (:message (fold-cljs :worker-exited)))))
  (testing "every folded reply is a well-formed :eval reply"
    (doseq [k (keys shadow-streams)
            :when (not (#{:switch :switch-no-watch :builds-none :builds-two} k))]
      (is (nil? (p/reply-problems :eval (assoc (fold-cljs k) :id 1))) (str k)))))

(deftest stopped-watches
  (is (repl/worker-exited? (shadow-streams :worker-exited)))
  (is (not (repl/worker-exited? (shadow-streams :no-runtime)))))

(deftest choosing-the-build
  (testing "the running builds are read from shadow's answer"
    (is (= #{} (repl/read-builds (some #(get % "value") (shadow-streams :builds-none)))))
    (is (= #{:app :other} (repl/read-builds (some #(get % "value") (shadow-streams :builds-two)))))
    (is (= #{} (repl/read-builds nil)))
    (is (= #{} (repl/read-builds "[:not :a-set]"))))
  (are [active named want] (= want (repl/choose-build active named))
    #{}            nil   {:failure :no-watch}
    #{:app}        nil   {:build :app}
    #{:app :other} nil   {:failure :several-builds :builds [:app :other]}
    #{:app :other} :app  {:build :app}
    #{:app}        :nope {:failure :build-not-running :build :nope}))

(deftest switching-a-session
  (is (repl/switched? (shadow-streams :switch)))
  (is (not (repl/switched? (shadow-streams :switch-no-watch))))
  (is (= "(shadow.cljs.devtools.api/repl :app)" (repl/switch-code :app))))

(deftest clojurescript-failures
  (are [failure status words] (let [[s m] (repl/failure-reply failure)]
                                (and (= status s) (re-find words m)))
    {:failure :no-shadow}                          :unavailable #"No shadow-cljs is running"
    {:failure :no-watch}                           :no-runtime  #"No shadow-cljs watch"
    {:failure :build-not-running :build :nope}     :no-runtime  #":nope named with --build"
    {:failure :several-builds :builds [:a :b]}     :unavailable #":a, :b.*--build"
    {:failure :cljs-interrupt}                     :unavailable #"cannot be interrupted"))
