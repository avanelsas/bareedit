(ns demo.core
  "A tiny ClojureScript program, the JS runtime of the shadow-cljs tests.")

(defn f
  "Returns 42, so a test can call a function of this namespace."
  []
  42)

(defn main
  "Keeps node running, so it stays the build's JS runtime."
  [& _]
  (js/setInterval (fn []) 1000000))
