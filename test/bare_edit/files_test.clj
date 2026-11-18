(ns bare-edit.files-test
  "Tests the path list, versions, reading, and compare-and-set saving and renaming on real folders."
  (:require [bare-edit.files :as files]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is testing]]))

(defn- project
  "A new temporary folder holding `files`, a map from project-relative path to text."
  [files]
  (let [root (fs/create-temp-dir)]
    (doseq [[rel text] files]
      (fs/create-dirs (fs/parent (fs/path root rel)))
      (spit (str (fs/path root rel)) text))
    root))

(deftest versions
  (let [root (project {"a.clj" "(ns a)"})]
    (is (= :absent (files/version (fs/path root "missing.clj"))))
    (is (re-matches #"[0-9a-f]{64}" (files/version (fs/path root "a.clj"))))
    (testing "a version depends on content only, so touching a file does not change it"
      (let [before (files/version (fs/path root "a.clj"))]
        (fs/set-last-modified-time (fs/path root "a.clj") (java.nio.file.attribute.FileTime/fromMillis 0))
        (is (= before (files/version (fs/path root "a.clj"))))))))

(deftest path-list-outside-git
  (let [root (project {"src/a.clj" "" "README.md" "" "node_modules/x/index.js" ""
                       "target/out.jar" "" ".shadow-cljs/cache" ""})]
    (is (= ["README.md" "src/a.clj"] (files/path-list root)))))

(deftest path-list-in-git
  (let [root (project {"src/a.clj" "" "src/b.cljs" "" "out/main.js" "" ".gitignore" "out/\n"})]
    (process/shell {:dir (str root) :out :string :err :string} "git" "init" "-q")
    (process/shell {:dir (str root) :out :string :err :string} "git" "add" "src/a.clj")
    (spit (str (fs/path root "src" "wéird name.clj")) "")
    (testing "tracked plus untracked files that are not ignored, with odd names intact"
      (is (= [".gitignore" "src/a.clj" "src/b.cljs" "src/wéird name.clj"] (files/path-list root))))
    (testing "a tracked file deleted on disk is dropped"
      (fs/delete (fs/path root "src" "a.clj"))
      (is (not-any? #{"src/a.clj"} (files/path-list root))))))

(deftest read-and-save
  (let [root (project {"src/a.clj" "(ns a)"})
        {:keys [text version]} (files/read-file root "src/a.clj")]
    (is (= "(ns a)" text))
    (testing "a missing file reads as empty and :absent"
      (is (= {:text "" :version :absent} (files/read-file root "src/new.clj"))))
    (testing "a save with the loaded version succeeds and returns the new version"
      (let [{v2 :version} (files/save! root "src/a.clj" "(ns a)\n(def x 1)" version)]
        (is (= v2 (:version (files/read-file root "src/a.clj"))))
        (testing "a second save with the stale version is refused"
          (is (= {:conflict v2} (files/save! root "src/a.clj" "lost" version)))
          (is (= "(ns a)\n(def x 1)" (:text (files/read-file root "src/a.clj")))))))
    (testing "saving a new file creates it and its folders"
      (is (string? (:version (files/save! root "src/deep/er/new.clj" "(ns new)" :absent))))
      (is (= "(ns new)" (:text (files/read-file root "src/deep/er/new.clj")))))
    (testing "creating over a file that appeared meanwhile is a conflict"
      (is (contains? (files/save! root "src/a.clj" "x" :absent) :conflict)))
    (testing "no temporary files are left behind"
      (is (empty? (fs/glob root "**/.bare-edit-*" {:hidden true}))))))

(deftest simultaneous-saves
  (testing "two saves from the same version: one wins, the other conflicts"
    (let [root    (project {})
          results (->> ["one" "two"]
                       (map #(future (files/save! root "a.clj" % :absent)))
                       doall
                       (map deref))]
      (is (= 1 (count (filter :version results))))
      (is (= 1 (count (filter :conflict results)))))))

(deftest failed-write
  (testing "a save onto a folder throws and leaves no temporary file"
    (let [root (project {"d/x.clj" ""})]
      (is (thrown? Exception (files/save! root "d" "text" :absent)))
      (is (empty? (fs/glob root "**/.bare-edit-*" {:hidden true}))))))

(deftest saving-keeps-permissions
  (let [root (project {"run.sh" "echo hi"})
        perms #(fs/posix->str (fs/posix-file-permissions (fs/path root %)))]
    (testing "an existing file keeps its permissions, such as an executable script"
      (fs/set-posix-file-permissions (fs/path root "run.sh") "rwxr-x---")
      (files/save! root "run.sh" "echo bye" (files/version (fs/path root "run.sh")))
      (is (= "rwxr-x---" (perms "run.sh"))))
    (testing "a new file gets the permissions any new file gets here"
      (fs/create-file (fs/path root "plain.txt"))
      (files/save! root "new.clj" "(ns new)" :absent)
      (is (= (perms "plain.txt") (perms "new.clj"))))))

(deftest renaming
  (let [root (project {"src/a.clj" "(ns a)" "src/b.clj" "(ns b)"})
        v    (files/version (fs/path root "src/a.clj"))]
    (testing "a file moves, into new folders too, and keeps its version"
      (is (= {:version v} (files/rename! root "src/a.clj" "src/util/c.clj" v)))
      (is (not (fs/exists? (fs/path root "src/a.clj"))))
      (is (= "(ns a)" (slurp (str (fs/path root "src/util/c.clj"))))))
    (testing "it never overwrites"
      (is (= {:exists true} (files/rename! root "src/util/c.clj" "src/b.clj" v)))
      (is (= "(ns b)" (slurp (str (fs/path root "src/b.clj"))))))
    (testing "a file changed on disk, or gone, conflicts"
      (is (= {:conflict (files/version (fs/path root "src/b.clj"))}
             (files/rename! root "src/b.clj" "src/d.clj" v)))
      (is (= {:conflict :absent} (files/rename! root "src/gone.clj" "src/d.clj" v))))))

(deftest renaming-to-another-case
  (let [root (project {"src/a.clj" "(ns a)"})
        v    (files/version (fs/path root "src/a.clj"))]
    (testing "a name that differs only in case is renamed, also where case is ignored"
      (is (= {:version v} (files/rename! root "src/a.clj" "src/A.clj" v)))
      (is (= ["A.clj"] (map (comp str fs/file-name) (fs/list-dir (fs/path root "src"))))))))
