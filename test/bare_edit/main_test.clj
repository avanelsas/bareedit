(ns bare-edit.main-test
  "Tests which project folder the command-line arguments choose."
  (:require [bare-edit.main :as main]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest are is]]))

(defn- folders
  "A temporary folder `base` holding the folder `proj` and the file `note.txt`.
  Returns `{:base :proj}` as canonical path strings."
  []
  (let [base (fs/canonicalize (fs/create-temp-dir))
        proj (fs/create-dirs (fs/path base "proj"))]
    (spit (str (fs/path base "note.txt")) "note")
    {:base (str base) :proj (str proj)}))

(deftest the-arguments-choose-the-project-folder
  (let [{:keys [base proj]} (folders)]
    (are [args want] (= want (main/project-root base args))
      []                     {:root base}
      ["proj"]               {:root proj}
      [proj]                 {:root proj}
      ["proj/.."]            {:root base}
      ["missing"]            {:problem :missing :path "missing"}
      ["note.txt"]           {:problem :not-a-folder :path "note.txt"}
      ["proj" "proj"]        {:problem :too-many :count 2})))

(deftest each-problem-has-a-message
  (are [problem text] (= text (main/problem-text problem))
    {:problem :too-many :count 2}             "Give one project folder, not 2."
    {:problem :missing :path "missing"}       "No such folder: missing"
    {:problem :not-a-folder :path "note.txt"} "Not a folder: note.txt"))

(deftest an-absolute-folder-ignores-the-current-folder
  (let [{:keys [proj]} (folders)]
    (is (= {:root proj} (main/project-root (str (fs/temp-dir)) [proj])))))
