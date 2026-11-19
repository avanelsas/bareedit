(ns bare-edit.protocol
  "The messages between the browser and the server, as data, used by both sides.
  A request is `{:op :id ...}`, its reply is `{:id :status ...}`, and an event the server
  sends unasked is `{:event ...}`. Messages travel as EDN text."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defn kind-for
  "The session kind for the file at `path`, or `nil` for a file that is not evaluated.
  It is `:clj` for `.clj` and `.cljc` files and `:cljs` for `.cljs` files. A `.cljc` file
  goes to Clojure, and its reader conditionals take their `:clj` branch."
  [path]
  (condp re-find (or path "")
    #"\.cljs$"      :cljs
    #"\.cljc?$"     :clj
    nil))

(def syntax-tokens
  "The names of BareDOM's syntax colour tokens, which colour code in the theme.
  The look and feel may override each, and the editor colours code with them. Token
  `:keyword` is the CSS variable `--x-color-syntax-keyword`."
  #{:comment :keyword :string :constant :function :type :tag})

(def statuses
  "The closed set of reply statuses. A `:running` status exists only in the browser."
  #{:ok :error :conflict :interrupted :no-runtime :unavailable})

(def kinds
  "The kinds a request value may be, each as a predicate and the words that name it."
  {:string   [string?                                    "a string"]
   :strings  [#(and (vector? %) (every? string? %))      "a vector of strings"]
   :int      [int?                                       "an integer"]
   :version  [#(or (= :absent %) (string? %))            "a version (a hash string or :absent)"]
   :ns       [#(or (nil? %) (string? %))                 "a namespace name or nil"]})

(def ops
  "The operations, each with its request keys and their kinds, and its reply keys.
  The reply keys are those of a reply whose status is `:ok`."
  {:paths       {:request {}                                             :reply #{:paths :unreadable}}
   :read        {:request {:paths :strings}                              :reply #{:files}}
   :save        {:request {:path :string :text :string :version :version} :reply #{:version}}
   :rename      {:request {:from :string :to :string :version :version}  :reply #{:version}}
   :eval        {:request {:path :string :ns :ns :line :int :code :string} :reply #{:out :err :value}}
   :load-file   {:request {:path :string :text :string}                  :reply #{:out :err :value}}
   :interrupt   {:request {:eval :int}                                   :reply #{}}
   :completions {:request {:prefix :string :ns :ns}                      :reply #{:candidates}}
   :lookup      {:request {:path :string :sym :string :ns :ns}           :reply #{:info}}
   :config      {:request {}                                             :reply #{:config :problems}}
   :project     {:request {}                                             :reply #{:name}}})

(def events
  "The events the server sends unasked."
  #{:out})

;; ── Building messages ───────────────────────────────────────────────────────

(defn request
  "A request for the operation `op` with the id `id` and the request keys in `args`."
  [op id args]
  (assoc args :op op :id id))

(defn reply
  "A reply with `status` to the request with `id`.
  `extra` holds the reply keys, or `:message` when the status is not `:ok`."
  ([id status] (reply id status {}))
  ([id status extra] (assoc extra :id id :status status)))

(defn failure
  "A reply with `status`, which is not `:ok`, to the request `id`, explained by `message`."
  [id status message]
  (reply id status {:message message}))

(defn event
  "An event of `kind`, such as `:out`, carrying the keys of `m`."
  [kind m]
  (assoc m :event kind))

;; ── Checking messages ───────────────────────────────────────────────────────

(defn request-problems
  "The problems with the incoming request `msg`, as sentences, or `nil` when it is well formed.
  It checks the `:op`, the integer `:id`, and the request keys [[ops]] gives for the
  operation, with their kinds."
  [{:keys [op id] :as msg}]
  (let [spec (get ops op)]
    (seq (cond-> []
           (not (map? msg))      (conj "A request must be a map.")
           (not (int? id))       (conj "A request needs an integer :id.")
           (not (keyword? op))   (conj "A request needs an :op keyword.")
           (and (keyword? op) (nil? spec)) (conj (str "Unknown operation " op "."))
           spec (into (for [[k kind] (sort (:request spec))
                            :let [[ok? words] (kinds kind)]
                            :when (not (and (contains? msg k) (ok? (get msg k))))]
                        (str "Operation " op " needs " k ", " words ".")))))))

(defn reply-problems
  "The problems with `msg`, a reply to `op`, as sentences, or `nil` when it is well formed.
  It checks the integer `:id`, the `:status`, the `:message` of a reply that is not `:ok`,
  and the reply keys of an `:ok` reply."
  [op {:keys [id status message] :as msg}]
  (seq (cond-> []
         (not (int? id))                        (conj "A reply needs the request's integer :id.")
         (not (contains? statuses status))      (conj (str "Unknown status " status "."))
         (and (not= :ok status) (not (string? message)))
         (conj "A reply that is not :ok needs a :message.")
         (= :ok status)
         (into (for [k (sort (get-in ops [op :reply]))
                     :when (not (contains? msg k))]
                 (str "An :ok reply to " op " needs " k "."))))))

;; ── Kinds and wire format ───────────────────────────────────────────────────

(defn kind
  "What `msg` is: `:request` (it has `:op`), `:event` (`:event`) or `:reply` (`:id`).
  Returns `nil` for anything else."
  [msg]
  (cond (contains? msg :op)    :request
        (contains? msg :event) :event
        (contains? msg :id)    :reply))

(defn encode
  "The message `msg` as EDN text."
  [msg]
  (pr-str msg))

(defn decode
  "The message in the EDN text `s`, or `nil` when `s` is not EDN."
  [s]
  (try (edn/read-string s)
       (catch #?(:clj Exception :cljs :default) _ nil)))
