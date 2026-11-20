(ns bare-edit.github-pages-demo.snapshot-test
  "Tests making the demo's snapshot from paths and bytes."
  (:require [bare-edit.github-pages-demo.snapshot :as snapshot]
            [clojure.test :refer [deftest is testing]]))

(deftest text-files
  (is (snapshot/text? (.getBytes "(ns a)\n" "UTF-8")))
  (is (snapshot/text? (byte-array 0)))
  (is (not (snapshot/text? (byte-array [-119 80 78 71 0 1])))))

(deftest a-snapshot
  (let [s (snapshot/snapshot "v0.2.0" [["src/b.clj" (.getBytes "(ns b)" "UTF-8")]
                                       ["logo.png"  (byte-array [-119 80 0])]
                                       ["README.md" (.getBytes "# Ü" "UTF-8")]])]
    (is (= "v0.2.0" (:version s)))
    (testing "text files only, sorted by path, read as UTF-8"
      (is (= [["README.md" "# Ü"] ["src/b.clj" "(ns b)"]] (seq (:files s)))))))
