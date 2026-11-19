(ns bare-edit.settings
  "The text of a new settings file, built from BareDOM's own presets.
  It names every setting with the value the preset gives it, as comments, so the reader sees
  the whole file and saving it unchanged gives the same look as no file."
  (:require [baredom.components.x-theme.model :as theme]
            [clojure.string :as str]))

(def colour-tokens
  "Each syntax colour of the settings, paired with the theme token that holds it.
  The settings text lists them in this order."
  [[:comment  theme/tk-color-syntax-comment]
   [:keyword  theme/tk-color-syntax-keyword]
   [:string   theme/tk-color-syntax-string]
   [:constant theme/tk-color-syntax-constant]
   [:function theme/tk-color-syntax-function]
   [:type     theme/tk-color-syntax-type]
   [:tag      theme/tk-color-syntax-tag]])

(defn- pixels
  "The size in pixels of the CSS length `s` in `rem` or `px`, or `nil` for any other unit.
  A `rem` is 16 pixels, the browser's default."
  [s]
  (when-let [[_ n unit] (re-matches #"([0-9.]+)(rem|px)" (str s))]
    (let [v (* (js/parseFloat n) (if (= unit "rem") 16 1))]
      (if (== v (js/Math.round v)) (js/Math.round v) v))))

(defn preset-values
  "The settings that the built-in preset named `preset` gives, as a settings map.
  Returns `{:font-family :font-size :syntax {:light :dark}}`, read from BareDOM's own tokens.
  The dark colours are the light ones with the preset's dark changes."
  [preset]
  (let [{:keys [light dark]} (theme/preset-tokens preset)
        colours (fn [tokens] (into {} (for [[k token] colour-tokens] [k (get tokens token)])))]
    {:font-family (get light theme/tk-font-family-mono)
     :font-size   (pixels (get light theme/tk-font-size-base))
     :syntax      {:light (colours light) :dark (colours (merge light dark))}}))

(defn- wrapped
  "The printed `items` on lines of at most `width` characters.
  The first line starts with `first-lead` and the others with `lead`."
  [first-lead lead width items]
  (reduce (fn [lines w]
            (let [line (peek lines)]
              (if (<= (+ (count line) 1 (count w)) width)
                (conj (pop lines) (str line " " w))
                (conj lines (str lead " " w)))))
          [first-lead] (map pr-str items)))

(defn- colour-lines
  "The lines of the `:syntax` map `syntax`, aligned, without their comment marks."
  [syntax]
  (let [light-open ":syntax {:light {"
        dark-open  "         :dark  {"
        width      (apply max (map (comp count str first) colour-tokens))
        indent     (apply str (repeat (count light-open) " "))
        entry      (fn [k v] (str (.padEnd (str k) width) " " (pr-str v)))
        block      (fn [open colours close]
                     (let [[e & more] (for [[k _] colour-tokens] (entry k (get colours k)))
                           lines      (into [(str open e)] (map #(str indent %)) more)]
                       (conj (pop lines) (str (peek lines) close))))]
    (concat (block light-open (:light syntax) "}")
            (block dark-open (:dark syntax) "}}"))))

(defn default-text
  "The text of a new settings file for the preset named `preset`.
  Without `preset`, it is BareDOM's default preset. Only `:preset` is set. Every other
  setting is a comment with the preset's value, since a set colour or font stays when the
  preset changes."
  ([] (default-text theme/default-preset))
  ([preset]
   (let [{:keys [font-family font-size syntax]} (preset-values preset)]
     (str/join
      "\n"
      (concat
       [";; BareEdit settings, for every project."
        ";; Save to apply. A wrong value is ignored and named in a warning."
        ";;"
        (str ";; The lines below that start with ;; show the values of the preset " (pr-str preset) ".")
        ";; Remove the ;; to change one. A colour you set stays when you change"
        ";; the preset, so set only the ones you want to differ."
        "{:theme"]
       (wrapped " {;; One of:" "  ;;        " 72 (sort theme/allowed-presets))
       [(str "  :preset " (pr-str preset))
        ""
        "  ;; The font of the editor and the results."
        (str "  ;; :font-family " (pr-str font-family))]
       (when font-size [(str "  ;; :font-size   " font-size)])
       [""
        "  ;; The colours of code, for light and dark mode."]
       (map #(str "  ;; " %) (colour-lines syntax))
       ["  }}"
        ""])))))
