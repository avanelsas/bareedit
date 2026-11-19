(ns bare-edit.config-test
  "Tests checking the look and feel file, as text in and a value out."
  (:require [bare-edit.config :as config]
            [clojure.test :refer [are deftest is testing]]))

(deftest defaults
  (testing "no file, an empty file and no :theme all give the defaults, with no problem"
    (doseq [text [nil "" "{}"]]
      (is (= {:config {:preset "default"} :problems []} (config/check text))))))

(deftest valid-values
  (is (= {:config {:preset "ocean" :font-family "JetBrains Mono, monospace" :font-size 14} :problems []}
         (config/check "{:theme {:preset \"ocean\" :font-family \"JetBrains Mono, monospace\" :font-size 14}}"))))

(deftest wrong-values-fall-back-one-by-one
  (is (= {:config   {:preset "default"}
          :problems [{:problem :unknown-key :path [:theem]}
                     {:problem :invalid :path [:theme :font-family] :value "a; b"
                      :must "a list of fonts, such as \"Menlo, monospace\""}
                     {:problem :invalid :path [:theme :font-size] :value "14" :must "a number of pixels, such as 14"}
                     {:problem :unknown-key :path [:theme :fontsize]}
                     {:problem :invalid :path [:theme :preset] :value "oceen"
                      :must (str "one of \"aurora\", \"default\", \"forest\", \"mono-ai\", \"neo-brutalist\", "
                                 "\"ocean\", \"sunset\", \"warm-mineral\"")}]}
         (config/check "{:theme {:preset \"oceen\" :font-family \"a; b\" :font-size \"14\" :fontsize 3} :theem 1}")))
  (testing "a good value stays when another is wrong"
    (is (= {:preset "forest"} (:config (config/check "{:theme {:preset \"forest\" :font-size -1}}"))))))

(deftest syntax-colours
  (testing "colours for each mode are kept"
    (is (= {:config   {:preset "default" :syntax {:light {:keyword "#a626a4"} :dark {:keyword "#c678dd" :string "#98c379"}}}
            :problems []}
           (config/check "{:theme {:syntax {:light {:keyword \"#a626a4\"} :dark {:keyword \"#c678dd\" :string \"#98c379\"}}}}"))))
  (testing "an unknown mode or token, a bad colour and a mode that is not a map are ignored one by one"
    (let [{:keys [config problems]}
          (config/check "{:theme {:syntax {:dark {:keyword \"red; x\" :strng \"red\" :string \"green\"} :dim {} :light 3}}}")]
      (is (= {:preset "default" :syntax {:dark {:string "green"}}} config))
      (is (= [[:invalid [:theme :syntax :dark :keyword]]
              [:unknown-key [:theme :syntax :dark :strng]]
              [:unknown-key [:theme :syntax :dim]]
              [:invalid [:theme :syntax :light]]]
             (map (juxt :problem :path) problems))))))

(deftest files-that-cannot-be-used
  (are [text problems] (= {:config {:preset "default"} :problems problems} (config/check text))
    "{:theme"           [{:problem :not-edn}]
    "[1 2]"             [{:problem :not-a-map}]
    "{:theme \"ocean\"}" [{:problem :invalid :path [:theme] :value "ocean" :must "a map"}]))

(deftest the-words-for-each-problem
  (are [problem text] (= text (config/problem-text problem))
    {:problem :not-edn}
    "config.edn is not valid EDN, so the defaults are used."
    {:problem :not-a-map}
    "config.edn must be a map, such as {:theme {:preset \"ocean\"}}, so the defaults are used."
    {:problem :unknown-key :path [:theem]}
    "Ignored the unknown key :theem in config.edn."
    {:problem :unknown-key :path [:theme :syntax :dark :strng]}
    "Ignored the unknown key :strng under :theme :syntax :dark in config.edn."
    {:problem :invalid :path [:theme :font-size] :value "14" :must "a number of pixels, such as 14"}
    "Ignored :theme :font-size \"14\" in config.edn: it must be a number of pixels, such as 14."))

