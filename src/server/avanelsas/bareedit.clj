(ns avanelsas.bareedit
  "The namespace bbin runs for the jar from Clojars. It starts BareEdit.
  bbin names it after the group and artifact, so the install command needs no main options."
  (:require [bare-edit.main :as main]))

(defn -main
  "Runs the `bareedit` command with the command-line arguments `args`.
  See [[bare-edit.main/-main]]."
  [& args]
  (apply main/-main args))
