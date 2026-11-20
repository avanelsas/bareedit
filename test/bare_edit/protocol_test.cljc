(ns bare-edit.protocol-test
  "Tests the message checks, the message kinds and the EDN wire format."
  (:require [bare-edit.protocol :as p]
            [clojure.test :refer [deftest is are testing]]))

(deftest request-problems
  (are [msg ok?] (= ok? (nil? (p/request-problems msg)))
    {:op :paths :id 1}                                     true
    {:op :save :id 2 :path "a.clj" :text "" :version :absent} true
    {:op :save :id 2 :path "a.clj"}                        false
    {:op :save :id 2 :path "a.clj" :text nil :version :absent} false
    {:op :save :id 2 :path "a.clj" :text "" :version :gone}  false
    {:op :read :id 2 :paths "a.clj"}                       false
    {:op :paths}                                           false
    {:op :paths :id "1"}                                   false
    {:op :nope :id 1}                                      false
    {:id 1}                                                false)
  (testing "each missing key is named"
    (is (= ["Operation :save needs :text, a string."
            "Operation :save needs :version, a version (a hash string or :absent)."]
           (p/request-problems {:op :save :id 3 :path "a.clj"})))))

(deftest reply-problems
  (are [op msg ok?] (= ok? (nil? (p/reply-problems op msg)))
    :paths {:id 1 :status :ok :paths [] :unreadable []}    true
    :paths {:id 1 :status :ok}                             false
    :save  {:id 1 :status :conflict :message "Changed on disk."} true
    :save  {:id 1 :status :conflict}                       false
    :save  {:id 1 :status :running :message "x"}           false
    :interrupt {:id 4 :status :ok}                         true
    :interrupt {:id 4 :status :ok :message "Keeps running."} true
    :project {:id 5 :status :ok :name "a"}                 true
    :project {:id 5 :status :ok :name "a" :read-only true} true
    :project {:id 5 :status :ok :name "a" :read-only "yes"} false)
  (testing "an optional key of the wrong kind is named"
    (is (= ["An :ok reply to :project needs :read-only to be true or false."]
           (p/reply-problems :project {:id 5 :status :ok :name "a" :read-only "yes"})))))

(deftest a-known-file
  (testing "a file whose version the browser knows comes without its text"
    (is (= {:version "v1"} (p/unless-known "v1" {:text "a" :version "v1"})))
    (is (= {:text "a" :version "v2"} (p/unless-known "v1" {:text "a" :version "v2"})))
    (is (= {:text "a" :version "v2"} (p/unless-known nil {:text "a" :version "v2"})))))

(deftest kinds
  (are [msg k] (= k (p/kind msg))
    (p/request :paths 1 {})          :request
    (p/reply 1 :ok {:paths []})      :reply
    (p/event :out {:text "hi"})      :event))

(deftest wire-format
  (testing "keywords, sets and :absent survive the round trip"
    (let [msg (p/reply 7 :ok {:files {"a.clj" {:text "(ns a)" :version :absent}}
                              :tags #{:x :y}})]
      (is (= msg (p/decode (p/encode msg))))))
  (testing "text that is not EDN reads as nil"
    (is (nil? (p/decode "{:op")))))

(deftest session-kinds
  (are [path kind] (= kind (p/kind-for path))
    "src/a.clj"   :clj
    "src/a.cljc"  :clj
    "src/a.cljs"  :cljs
    "deps.edn"    nil
    "README.md"   nil
    nil           nil))
