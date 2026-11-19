(ns bare-edit.settings-test
  "Tests the text of a new settings file against BareDOM's own preset values."
  (:require [bare-edit.protocol :as p]
            [bare-edit.settings :as settings]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- uncommented
  "The text `text` with the comment mark taken off every line that holds a setting."
  [text]
  (str/replace text #"(?m)^(\s*);; (\s*:)" "$1$2"))

(deftest the-default-text
  (let [text (settings/default-text)]
    (testing "as it is, it sets the preset only"
      (is (= {:theme {:preset "default"}} (reader/read-string text))))
    (testing "with the comment marks taken off, it holds every setting with the preset's value"
      (let [theme (:theme (reader/read-string (uncommented text)))]
        (is (= (assoc (settings/preset-values "default") :preset "default" :ui-font-size 16 :ruler 80) theme))
        (is (= "#ff7b72" (get-in theme [:syntax :dark :keyword])))
        (is (= 16 (:font-size theme)))))
    (testing "it names every preset"
      (doseq [nm ["aurora" "default" "forest" "mono-ai" "neo-brutalist" "ocean" "sunset" "warm-mineral"]]
        (is (str/includes? text (pr-str nm)) nm)))))

(deftest every-colour-in-each-mode
  (is (= (set (map first settings/colour-tokens)) p/syntax-tokens))
  (doseq [mode [:light :dark]]
    (is (every? string? (vals (get-in (settings/preset-values "ocean") [:syntax mode]))) mode)))
