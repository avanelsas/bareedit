(ns bare-edit.github-pages-demo.snapshot-test
  "Tests making the demo's snapshot from paths and bytes, and where the demo is built."
  (:require [babashka.fs :as fs]
            [bare-edit.github-pages-demo.snapshot :as snapshot]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
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

(defn- output-folder
  "The folder the demo's build in `shadow-cljs.edn` writes into, the parent of its `js` folder."
  []
  (-> (slurp "shadow-cljs.edn") edn/read-string
      (get-in [:builds :github-pages-demo :output-dir])
      fs/parent str))

(defn- uploaded-folder
  "The folder the GitHub Pages workflow uploads, the `path` of its upload-pages-artifact step."
  []
  (->> (yaml/parse-string (slurp ".github/workflows/github-pages-demo.yml"))
       :jobs vals (mapcat :steps)
       (filter #(some-> (:uses %) (str/starts-with? "actions/upload-pages-artifact")))
       first :with :path))

(deftest one-output-folder
  (testing "the workflow uploads the folder the demo is built into"
    (is (= (output-folder) (uploaded-folder)))))
