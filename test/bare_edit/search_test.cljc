(ns bare-edit.search-test
  "Tests finding text, the same in Clojure and in ClojureScript."
  (:require [bare-edit.search :as search]
            [clojure.test :refer [deftest are is testing]]))

(defn- ranges
  "The places where the query `q`, see [[bare-edit.search/query?]], matches `line`."
  [q line]
  (search/line-ranges (:pattern (search/pattern q)) line))

(deftest queries
  (are [q line want] (= want (ranges q line))
    {:text "a"}                    "a A a"          [[0 1] [2 3] [4 5]]
    {:text "a" :case true}         "a A a"          [[0 1] [4 5]]
    {:text "a.b"}                  "a.b axb"        [[0 3]]
    {:text "a.b" :regexp true}     "a.b axb"        [[0 3] [4 7]]
    {:text "(+ 1"}                 "(+ 1 2)"        [[0 4]]
    {:text "map" :word true}       "map mapv (map"  [[0 3] [10 13]]
    {:text "x*" :regexp true}      "axxb"           [[1 3]])
  (testing "an empty query has no pattern, and a broken one has a problem"
    (is (nil? (search/pattern {:text ""})))
    (is (string? (:problem (search/pattern {:text "(" :regexp true}))))))

(deftest searching-files
  (let [files [["src/a.clj" "(defn g [])"] ["src/b.clj" "(ns b)\n  (defn f [])\n"]]]
    (is (= {:matches [{:path "src/a.clj" :line 1 :text "(defn g [])"}
                      {:path "src/b.clj" :line 2 :text "(defn f [])"}]
            :more    false}
           (search/search {:text "defn"} files)))
    (testing "past the limit, the rest is left out"
      (is (= {:matches [{:path "src/a.clj" :line 1 :text "(defn g [])"}] :more true}
             (search/found (:pattern (search/pattern {:text "defn"})) files 1))))
    (testing "the search stops reading one match past the limit"
      (let [seen (atom [])
            lazy (map (fn [p] (swap! seen conj p) [p "x"]) '("a" "b" "c" "d"))]
        (search/found (:pattern (search/pattern {:text "x"})) lazy 1)
        (is (<= (count @seen) 2))))
    (testing "found keeps the whole line, the reply cuts it"
      (let [q {:text "defn f"}]
        (is (= "  (defn f [])" (:text (first (:matches (search/found (:pattern (search/pattern q)) files 5))))))
        (is (= "(defn f [])" (:text (first (:matches (search/search q files))))))))
    (is (= {:matches [] :more false} (search/search {:text ""} files)))
    (is (contains? (search/search {:text "[" :regexp true} files) :problem))))
