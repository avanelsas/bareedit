(ns bare-edit.github-pages-demo.server-test
  "Tests that the stand-in server answers every operation in the protocol's own terms."
  (:require [bare-edit.github-pages-demo.server :as server]
            [bare-edit.protocol :as p]
            [clojure.test :refer [deftest is testing]]))

(def snapshot
  "A small snapshot for the tests."
  {:version "v1" :files {"src/a.clj" "(ns a)" "README.md" "# A"}})

(defn- ask
  "The reply of the stand-in with `s`, by default [[snapshot]], to `op` with `args`, as data."
  ([op args] (ask snapshot op args))
  ([s op args] (p/decode (server/answer s (p/encode (p/request op 1 args))))))

(deftest every-operation
  (testing "each operation of the protocol gets a well-formed reply"
    (doseq [[op {:keys [request]}] p/ops
            :let [args (into {} (for [[k kind] request] [k (nth (p/kinds kind) 2)]))]]
      (is (nil? (p/reply-problems op (ask op args))) (str op)))))

(deftest the-project
  (is (= {:id 1 :status :ok :name "bareedit" :read-only true} (ask :project {})))
  (is (= p/default-config (:config (ask :config {})))))

(deftest reading
  (is (= ["README.md" "src/a.clj"] (:paths (ask :paths {}))))
  (is (= {"src/a.clj" {:text "(ns a)" :version "v1"}
          "src/b.clj" {:text "" :version :absent}}
         (:files (ask :read {:paths ["src/a.clj" "src/b.clj"] :known {}}))))
  (testing "a file the editor knows comes without its text"
    (is (= {"src/a.clj" {:version "v1"}}
           (:files (ask :read {:paths ["src/a.clj"] :known {"src/a.clj" "v1"}}))))))

(deftest searching
  (is (= {:id 1 :status :ok :matches [{:path "src/a.clj" :line 1 :text "(ns a)"}] :more false}
         (ask :search {:query {:text "ns" :case false :regexp false :word false}})))
  (is (= :error (:status (ask :search {:query {:text "(" :case false :regexp true :word false}})))))

(deftest a-missing-snapshot
  (let [s {:problem "Could not load the demo's files: the server answered 404."}]
    (testing "the files say why they are missing"
      (is (= {:id 1 :status :error :message (:problem s)} (ask s :paths {})))
      (is (= {:id 1 :status :error :message (:problem s)} (ask s :read {:paths ["a"] :known {}}))))
    (testing "the project still answers"
      (is (= :ok (:status (ask s :project {})))))))

(deftest nothing-changes
  (doseq [[op args] [[:save {:path "src/a.clj" :text "" :version "v1"}]
                     [:rename {:from "src/a.clj" :to "src/b.clj" :version "v1"}]
                     [:save-settings {:text "" :version :absent}]
                     [:eval {:path "src/a.clj" :ns "a" :line 1 :code "1"}]]]
    (is (= {:id 1 :status :unavailable :message server/refusal} (ask op args)) (str op))))

(deftest malformed
  (is (= :error (:status (ask :save {}))))
  (is (nil? (server/answer snapshot "not edn {"))))
