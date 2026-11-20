(ns bare-edit.config
  "The user's look and feel, read from one EDN file and checked.
  The file is `~/.config/bareedit/config.edn`, one per user, for every project. It holds a
  `:theme` map with a `:preset`, and optionally a `:ui-font-size`, a `:ruler`, a
  `:font-family`, a `:font-size` and the `:syntax` colours for light and dark mode. A
  missing file or key falls back to the default. A wrong value is left out and named in a
  problem, a sentence the browser shows once, so the editor always starts."
  (:require [bare-edit.protocol :as p]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def presets
  "The names of BareDOM's built-in `x-theme` presets.
  This is a copy of BareDOM's own list, because the server runs in Babashka and cannot read
  BareDOM's ClojureScript."
  #{"default" "ocean" "forest" "sunset" "neo-brutalist" "aurora" "mono-ai" "warm-mineral"})

(defn- css-value?
  "Whether `x` can go into a CSS declaration: a non-blank string without `;`, `{` or `}`."
  [x]
  (and (string? x) (not (str/blank? x)) (not (re-find #"[;{}]" x))))

(def ^:private mode-colours
  "The rule for the syntax colours of one mode: a CSS colour for each of the theme's tokens."
  {:keys (zipmap p/syntax-tokens
                 (repeat {:valid? css-value? :must "a CSS colour, such as \"#c678dd\""}))})

(def rules
  "What the file may hold, as nested rules keyed like the file.
  A rule with `:keys` is a map whose keys have rules of their own. Any other rule is a value,
  with the test `:valid?` it must pass and `:must`, what it must be. A font list and a
  colour must not hold `;`, `{` or `}`, since they go into a CSS declaration. `:syntax`
  overrides the theme's syntax colours, for `:light` and `:dark` mode apart."
  {:theme {:keys {:preset      {:valid? presets
                                :must   (str "one of " (str/join ", " (map pr-str (sort presets))))}
                  :ui-font-size (let [{:keys [min max]} p/ui-font-sizes]
                                  {:valid? #(and (number? %) (<= min % max))
                                   :must   (str "a number of pixels from " min " to " max ", such as 16")})
                  :ruler        (let [{:keys [min max]} p/ruler-columns]
                                  {:valid? #(or (false? %) (and (int? %) (<= min % max)))
                                   :must   (str "a column from " min " to " max ", or false for none")})
                  :font-family {:valid? css-value?
                                :must   "a list of fonts, such as \"Menlo, monospace\""}
                  :font-size   {:valid? #(and (number? %) (pos? %))
                                :must   "a number of pixels, such as 14"}
                  :syntax      {:keys {:light mode-colours
                                       :dark  mode-colours}}}}})

(def default-file
  "Where the file is: `~/.config/bareedit/config.edn`."
  (str (fs/path (fs/home) ".config" "bareedit" "config.edn")))

(defn read-file
  "The text of the file at `path`, or `nil` when there is no file."
  [path]
  (when (fs/exists? path) (slurp (str path))))

(defn- check-map
  "The check of the map `m` at `path` in the file against `rules`, the rules of its keys.
  Returns `{:value value :problems problems}`: `value` holds what passed, and each other
  key or value is a problem, named by its `:path` in the file."
  [rules m path]
  (reduce (fn [acc [k v]]
            (let [p    (conj path k)
                  rule (rules k)]
              (cond
                (nil? rule)
                (update acc :problems conj {:problem :unknown-key :path p})

                (:keys rule)
                (if (map? v)
                  (let [sub (check-map (:keys rule) v p)]
                    (-> acc (assoc-in [:value k] (:value sub)) (update :problems into (:problems sub))))
                  (update acc :problems conj {:problem :invalid :path p :value v :must "a map"}))

                ((:valid? rule) v)
                (assoc-in acc [:value k] v)

                :else
                (update acc :problems conj {:problem :invalid :path p :value v :must (:must rule)}))))
          {:value {} :problems []}
          (sort-by (comp str key) m)))

(defn check
  "The look and feel in `text`, the file's text, or `nil` when there is no file.
  Returns `{:config config :problems problems}`. `config` is the `:theme` the file gives,
  checked against [[rules]], over the `:preset` of [[bare-edit.protocol/default-config]].
  `problems` is a vector of maps, one for each thing that was ignored, named by
  `:problem`: `:not-edn` and
  `:not-a-map` fall back to all the defaults, `:unknown-key` names its `:path` in the file,
  and `:invalid` names the `:path`, the `:value` and what it `:must` be. See
  [[problem-text]] for the words."
  [text]
  (let [data (try (edn/read-string (or text "")) (catch Exception _ ::unreadable))]
    (cond
      (= ::unreadable data) {:config p/default-config :problems [{:problem :not-edn}]}
      (nil? data)           {:config p/default-config :problems []}
      (not (map? data))     {:config p/default-config :problems [{:problem :not-a-map}]}
      :else                 (let [{:keys [value problems]} (check-map rules data [])]
                              {:config (merge p/default-config (:theme value)) :problems problems}))))

(defn- path-text
  "The keys of `path` as the file writes them, separated by spaces."
  [path]
  (str/join " " path))

(defn problem-text
  "The sentence that tells the user about `problem`, one of the maps of [[check]]."
  [{:keys [problem path value must]}]
  (case problem
    :not-edn     "config.edn is not valid EDN, so the defaults are used."
    :not-a-map   "config.edn must be a map, such as {:theme {:preset \"ocean\"}}, so the defaults are used."
    :unknown-key (str "Ignored the unknown key " (last path)
                      (when (next path) (str " under " (path-text (butlast path)))) " in config.edn.")
    :invalid     (str "Ignored " (path-text path) " " (pr-str value) " in config.edn: it must be " must ".")))
