(ns bare-edit.config-test
  "Tests checking the look and feel file, as text in and a value out."
  (:require [bare-edit.config :as config]
            [clojure.test :refer [are deftest is testing]]))

(deftest defaults
  (testing "no file, an empty file and no :theme all give the defaults, with no problem"
    (doseq [text [nil "" "{}"]]
      (is (= {:config {:preset "default"} :problems []} (config/check text))))))

(deftest valid-values
  (is (= {:config {:preset "ocean" :font-mono "JetBrains Mono, monospace" :font-size 14} :problems []}
         (config/check "{:theme {:preset \"ocean\" :font-mono \"JetBrains Mono, monospace\" :font-size 14}}"))))

(deftest wrong-values-fall-back-one-by-one
  (is (= {:config   {:preset "default"}
          :problems [{:problem :unknown-key :key :theem}
                     {:problem :invalid :key :font-mono :value "a; b"}
                     {:problem :invalid :key :font-size :value "14"}
                     {:problem :unknown-key :key :fontsize :in :theme}
                     {:problem :invalid :key :preset :value "oceen"}]}
         (config/check "{:theme {:preset \"oceen\" :font-mono \"a; b\" :font-size \"14\" :fontsize 3} :theem 1}")))
  (testing "a good value stays when another is wrong"
    (is (= {:config   {:preset "forest"}
            :problems [{:problem :invalid :key :font-size :value -1}]}
           (config/check "{:theme {:preset \"forest\" :font-size -1}}")))))

(deftest files-that-cannot-be-used
  (are [text problem] (= {:config {:preset "default"} :problems [{:problem problem}]} (config/check text))
    "{:theme"           :not-edn
    "[1 2]"             :not-a-map
    "{:theme \"ocean\"}" :theme-not-a-map))

(deftest the-words-for-each-problem
  (are [problem text] (= text (config/problem-text problem))
    {:problem :not-edn}
    "config.edn is not valid EDN, so the defaults are used."
    {:problem :not-a-map}
    "config.edn must be a map, such as {:theme {:preset \"ocean\"}}, so the defaults are used."
    {:problem :theme-not-a-map}
    "Ignored :theme in config.edn: it must be a map."
    {:problem :unknown-key :key :theem}
    "Ignored the unknown key :theem in config.edn."
    {:problem :unknown-key :key :fontsize :in :theme}
    "Ignored the unknown key :fontsize under :theme in config.edn."
    {:problem :invalid :key :font-size :value "14"}
    "Ignored :font-size \"14\" in config.edn: it must be a number of pixels, such as 14."
    {:problem :invalid :key :preset :value "oceen"}
    (str "Ignored :preset \"oceen\" in config.edn: it must be one of \"aurora\", \"default\", \"forest\", "
         "\"mono-ai\", \"neo-brutalist\", \"ocean\", \"sunset\", \"warm-mineral\".")))
