(ns bare-edit.main-test
  "Tests which project folder the command-line arguments choose."
  (:require [bare-edit.main :as main]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest are is testing]]))

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

(deftest the-options
  (is (= {:opts {:no-open true :build :app} :args ["proj"]}
         (main/parse-command ["--no-open" "--build" "app" "proj"])))
  (is (= {:help true} (:opts (main/parse-command ["-h"]))))
  (is (= {:version true} (:opts (main/parse-command ["-v"]))))
  (testing "an unknown option is a problem, not ignored"
    (is (= {:problem :unknown-option :option :verison} (main/parse-command ["--verison"])))
    (is (= "Unknown option --verison. Run bareedit --help to see the options."
           (main/problem-text {:problem :unknown-option :option :verison})))
    (is (= "Unknown option -x. Run bareedit --help to see the options."
           (main/problem-text {:problem :unknown-option :option :x})))))

(deftest the-help-names-every-option
  (doseq [option ["--build" "--no-open" "--version" "--help" "-v" "-h"]]
    (is (str/includes? main/usage option) option)))

(deftest the-version-from-a-checkout
  (is (re-matches #"\S+ \(\d{4}-\d{2}-\d{2}\)" (main/version))))

(deftest what-the-command-does
  (let [{:keys [base proj]} (folders)
        does (fn [& args] (main/action base args))]
    (is (= {:action :start :root proj :opts {}} (does "proj")))
    (is (= {:action :start :root base :opts {:no-open true}} (does "--no-open")))
    (testing "help and version stop before the folder is looked at"
      (is (= {:action :help} (does "--help" "missing")))
      (is (= {:action :version} (does "-v" "missing"))))
    (testing "an unknown option fails before help"
      (is (= :fail (:action (does "--help" "--verison")))))
    (testing "a bad folder fails only when starting"
      (is (= {:action :fail :text "No such folder: missing"} (does "missing"))))))
