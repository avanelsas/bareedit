(ns bare-edit.github-pages-demo.server
  "A stand-in for the BareEdit server that shows a snapshot of a project and changes nothing.
  It answers the same messages as the server, from a snapshot, `{:version :files}`, where
  `:files` maps each project-relative path to its text. A snapshot that could not be loaded
  is `{:problem message}` instead. The stand-in names the project as read-only, lists,
  reads and searches the files, and refuses every other operation, such as a save or an evaluation, as
  `:unavailable`, so the editor shows why."
  (:require [bare-edit.protocol :as p]
            [bare-edit.search :as search]
            [clojure.string :as str]))

(def project-name
  "The project's name: the snapshot is of BareEdit's own repository."
  "bareedit")

(def refusal
  "The message of every operation the demo refuses."
  "This read-only demo cannot do that. To use BareEdit, install it from github.com/avanelsas/bareedit.")

(defmulti reply
  "The reply to the well-formed request `req`, from `snapshot`, dispatched on the request's `:op`.
  `:project` names the project as read-only, and `:config` gives the default look. `:paths`
  lists the files, `:read` gives each file, see [[bare-edit.protocol/unless-known]], and
  `:search` searches them, see [[bare-edit.search/search]]. A path that is not in the
  snapshot is `:absent`. All three answer `:error` with the snapshot's `:problem` when it
  has one. Any other operation is refused."
  (fn [_snapshot req] (:op req)))

(defmethod reply :default [_snapshot {:keys [id]}]
  (p/failure id :unavailable refusal))

(defmethod reply :project [_snapshot {:keys [id]}]
  (p/reply id :ok {:name project-name :read-only true}))

(defmethod reply :config [_snapshot {:keys [id]}]
  (p/reply id :ok {:config p/default-config :problems []}))

(defmethod reply :paths [{:keys [files problem]} {:keys [id]}]
  (if problem
    (p/failure id :error problem)
    (p/reply id :ok {:paths (vec (sort (keys files))) :unreadable []})))

(defmethod reply :read [{:keys [version files problem]} {:keys [id known] paths :paths}]
  (if problem
    (p/failure id :error problem)
    (p/reply id :ok {:files (into {} (for [path paths]
                                       [path (p/unless-known (get known path)
                                                             (if (contains? files path)
                                                               {:text (get files path) :version version}
                                                               {:text "" :version :absent}))]))})))

(defmethod reply :search [{:keys [files problem]} {:keys [id query]}]
  (let [answer (when-not problem (search/search query (sort files)))]
    (cond problem           (p/failure id :error problem)
          (:problem answer) (p/failure id :error (:problem answer))
          :else             (p/reply id :ok answer))))

(defn answer
  "The reply to `text`, one message from the editor, as EDN text, or `nil` when there is none.
  Text that is not EDN, or a message that is not a map with an integer `:id`, has no reply.
  A malformed request is answered `:error` with its problems, and a well-formed one by
  [[reply]] from `snapshot`."
  [snapshot text]
  (let [msg (p/decode text)]
    (when (and (map? msg) (int? (:id msg)))
      (p/encode (if-let [problems (p/request-problems msg)]
                  (p/failure (:id msg) :error (str/join " " problems))
                  (reply snapshot msg))))))
