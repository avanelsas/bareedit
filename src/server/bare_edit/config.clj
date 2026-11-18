(ns bare-edit.config
  "The user's look and feel, read from one EDN file and checked.
  The file is `~/.config/bare-edit/config.edn`, one per user, for every project. It holds a
  `:theme` map with a `:preset`, and optionally a `:font-mono` and a `:font-size`. A
  missing file or key falls back to the default. A wrong value is left out and named in a
  problem, a sentence the browser shows once, so the editor always starts."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def presets
  "The names of BareDOM's built-in `x-theme` presets.
  This is a copy of BareDOM's own list, because the server runs in Babashka and cannot read
  BareDOM's ClojureScript."
  #{"default" "ocean" "forest" "sunset" "neo-brutalist" "aurora" "mono-ai" "warm-mineral"})

(def defaults
  "The look and feel when the file names nothing: the preset `\"default\"`.
  The fonts are then the preset's own."
  {:preset "default"})

(def theme-keys
  "For each key under `:theme`, the test its value must pass and what it must be.
  A font list must not hold `;`, `{` or `}`, since it goes into a CSS declaration."
  {:preset    {:valid? presets
               :must   (str "one of " (str/join ", " (map pr-str (sort presets))))}
   :font-mono {:valid? #(and (string? %) (not (str/blank? %)) (not (re-find #"[;{}]" %)))
               :must   "a list of fonts, such as \"Menlo, monospace\""}
   :font-size {:valid? #(and (number? %) (pos? %))
               :must   "a number of pixels, such as 14"}})

(def default-file
  "Where the file is: `~/.config/bare-edit/config.edn`."
  (str (fs/path (fs/home) ".config" "bare-edit" "config.edn")))

(defn read-file
  "The text of the file at `path`, or `nil` when there is no file."
  [path]
  (when (fs/exists? path) (slurp (str path))))

(defn- all-defaults
  "The check for a file that cannot be used at all, with the problem `problem`."
  [problem]
  {:config defaults :problems [{:problem problem}]})

(defn- check-theme
  "The check of `theme`, the `:theme` map, as `{:config config :problems problems}`.
  Each valid value goes over the default, and each other key or value is a problem."
  [theme]
  (reduce (fn [acc [k v]]
            (if-let [{:keys [valid?]} (theme-keys k)]
              (if (valid? v)
                (update acc :config assoc k v)
                (update acc :problems conj {:problem :invalid :key k :value v}))
              (update acc :problems conj {:problem :unknown-key :key k :in :theme})))
          {:config defaults :problems []}
          (sort-by (comp str key) theme)))

(defn check
  "The look and feel in `text`, the file's text, or `nil` when there is no file.
  Returns `{:config config :problems problems}`. `config` holds the `:preset`, and the
  `:font-mono` and `:font-size` when the file gives valid ones. `problems` is a vector of
  maps, one for each thing that was ignored, named by `:problem`: `:not-edn`, `:not-a-map`
  and `:theme-not-a-map` fall back to all the defaults, `:unknown-key` names its `:key`,
  and its map `:in`, if any, and `:invalid` names the `:key` and its `:value`. See
  [[problem-text]] for the words."
  [text]
  (let [data (try (edn/read-string (or text "")) (catch Exception _ ::unreadable))]
    (cond
      (= ::unreadable data) (all-defaults :not-edn)
      (nil? data)           {:config defaults :problems []}
      (not (map? data))     (all-defaults :not-a-map)
      :else
      (let [theme    (get data :theme {})
            problems (vec (for [k (sort-by str (keys data)) :when (not= :theme k)]
                            {:problem :unknown-key :key k}))]
        (if (map? theme)
          (update (check-theme theme) :problems #(into problems %))
          {:config defaults :problems (conj problems {:problem :theme-not-a-map})})))))

(defn problem-text
  "The sentence that tells the user about `problem`, one of the maps of [[check]]."
  [{:keys [problem key value in]}]
  (case problem
    :not-edn         "config.edn is not valid EDN, so the defaults are used."
    :not-a-map       "config.edn must be a map, such as {:theme {:preset \"ocean\"}}, so the defaults are used."
    :theme-not-a-map "Ignored :theme in config.edn: it must be a map."
    :unknown-key     (str "Ignored the unknown key " key (when in (str " under " in)) " in config.edn.")
    :invalid         (str "Ignored " key " " (pr-str value) " in config.edn: it must be "
                          (get-in theme-keys [key :must]) ".")))
