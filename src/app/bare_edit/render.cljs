(ns bare-edit.render
  "A small hiccup renderer that patches the DOM in place.
  Children are matched by position: a child of the same tag is patched, any other is
  replaced. An element with `:data-keep true` keeps its children as they are, for children
  that something else owns, such as a CodeMirror view or a toaster's toasts. A prop key in
  the `prop` namespace, such as `:prop/items`, sets a JS property instead of an attribute,
  with the value converted to JS, and only when the value changed. A `:key` prop is not set
  on the element: an element whose key changes is replaced, not patched."
  (:require [clojure.string :as str]))

;;; ── Prop helpers ──────────────────────────────────────────────────────────

(defn- on-key?
  "Whether the prop key `k` is an event listener, such as `:on-click`."
  [k]
  (str/starts-with? (name k) "on-"))

(defn- property-key?
  "Whether the prop key `k` names a JS property, such as `:prop/items`."
  [k]
  (= "prop" (namespace k)))

(defn- event-name
  "The DOM event name for the listener key `k`, without its `on-` prefix.
  `:on-click` gives `\"click\"` and `:on-x-modal-dismiss` gives `\"x-modal-dismiss\"`."
  [k]
  (subs (name k) 3))

(def ^:private listeners-key
  "The element property that holds an element's current listeners, by event name."
  "__bd_listeners")

(def ^:private props-key
  "The element property that holds the props an element was last rendered with."
  "__bd_props")

(defn- set-listener!
  "Makes `handler` the listener of `el` for the event of key `k`, replacing an earlier one."
  [^js el k handler]
  (let [ename (event-name k)
        store (or (aget el listeners-key) #js {})]
    (when-let [old (aget store ename)]
      (.removeEventListener el ename old))
    (.addEventListener el ename handler)
    (aset store ename handler)
    (aset el listeners-key store)))

(defn- remove-listener!
  "Removes the listener of `el` for the event of key `k`, if it has one."
  [^js el k]
  (let [ename (event-name k)
        store (aget el listeners-key)]
    (when-let [old (and store (aget store ename))]
      (.removeEventListener el ename old)
      (js-delete store ename))))

(defn- set-prop!
  "Sets the prop `k` of `el` to `v`.
  A listener key sets a listener, and a property key sets the property to `v` as JS. For an
  attribute, `nil` and `false` remove it, `true` sets it empty, and any other value sets it
  as a string."
  [^js el k v]
  (cond
    (= :key k)        nil
    (property-key? k) (aset el (name k) (clj->js v))
    (on-key? k)  (set-listener! el k v)
    (nil? v)     (.removeAttribute el (name k))
    (true? v)    (.setAttribute el (name k) "")
    (false? v)   (.removeAttribute el (name k))
    :else        (.setAttribute el (name k) (str v))))

(defn- store-props!
  "Remembers `props` on `el`, so the next render can compare against them."
  [^js el props]
  (aset el props-key props))

(defn- get-stored-props
  "The props `el` was last rendered with, or `{}`."
  [^js el]
  (or (aget el props-key) {}))

;;; ── Hiccup parsing ───────────────────────────────────────────────────────

(defn- parse-vnode
  "Splits the hiccup vector `v` into `[tag props children]`; `props` is `{}` when absent."
  [v]
  (let [[tag & args] v
        has-props? (and (seq args) (map? (first args)))
        props      (if has-props? (first args) {})
        children   (if has-props? (rest args) args)]
    [tag props children]))

(defn- flatten-children
  "The renderable nodes in `children`.
  Nested seqs are spliced in, numbers become strings, and `nil` and `false` are dropped."
  [children]
  (persistent!
   (reduce (fn [acc x]
             (cond
               (nil? x)    acc
               (false? x)  acc
               (string? x) (conj! acc x)
               (number? x) (conj! acc (str x))
               (vector? x) (conj! acc x)
               (seq? x)    (reduce conj! acc (flatten-children x))
               :else       acc))
           (transient [])
           children)))

;;; ── DOM creation ──────────────────────────────────────────────────────────

(declare create-node)

(defn- create-element
  "A new DOM element for the hiccup vector `v`, with its props and children."
  [v]
  (let [[tag props children] (parse-vnode v)
        ^js el (.createElement js/document (name tag))]
    (doseq [[k val] props]
      (set-prop! el k val))
    (store-props! el props)
    (doseq [child (flatten-children children)]
      (.appendChild el (create-node child)))
    el))

(defn- create-node
  "A new DOM node for `x`: a text node for a string, an element for a vector."
  [x]
  (cond
    (string? x) (.createTextNode js/document x)
    (vector? x) (create-element x)
    :else       (.createTextNode js/document "")))

;;; ── Reconciler ────────────────────────────────────────────────────────────

(defn- patch-props!
  "Updates the props of `el` from `old-props` to `new-props`, touching only what changed."
  [^js el old-props new-props]
  ;; Remove props that no longer exist
  (doseq [[k _] old-props]
    (when-not (contains? new-props k)
      (cond (= :key k)        nil
            (property-key? k) (aset el (name k) nil)
            (on-key? k)       (remove-listener! el k)
            :else             (.removeAttribute el (name k)))))
  ;; Add / update changed props
  (doseq [[k v] new-props]
    (let [old-v (get old-props k)]
      (when (not= v old-v)
        (set-prop! el k v)))))

(declare patch-children!)

(defn- same-tag?
  "Whether `dom-node` is an element with the tag and the `:key` of the hiccup vector `vnode`."
  [^js dom-node vnode]
  (and (= 1 (.-nodeType dom-node))
       (vector? vnode)
       (= (str/upper-case (name (first vnode)))
          (.-tagName dom-node))
       (= (:key (get-stored-props dom-node)) (:key (second (parse-vnode vnode))))))

(defn- text-node?
  "Whether `node` is a text node."
  [^js node]
  (= 3 (.-nodeType node)))

(defn- patch-node!
  "Makes `old-node`, a child of `parent`, match `new-vnode`.
  A text node or an element of the same tag is patched in place; anything else is replaced."
  [^js parent ^js old-node new-vnode]
  (cond
    ;; Text → text: update in place
    (and (string? new-vnode) (text-node? old-node))
    (when (not= new-vnode (.-nodeValue old-node))
      (set! (.-nodeValue old-node) new-vnode))

    ;; Element with same tag: patch attributes + recurse children
    (same-tag? old-node new-vnode)
    (let [[_ new-props new-children] (parse-vnode new-vnode)
          old-props (get-stored-props old-node)]
      (patch-props! old-node old-props new-props)
      (store-props! old-node new-props)
      (when-not (:data-keep new-props)
        (patch-children! old-node (flatten-children new-children))))

    ;; Type or tag mismatch: replace
    :else
    (.replaceChild parent (create-node new-vnode) old-node)))

(defn- patch-children!
  "Makes the children of `parent` match `new-vnodes`, position by position."
  [^js parent new-vnodes]
  (let [old-nodes (array-seq (.-childNodes parent))
        old-count (count old-nodes)
        new-count (count new-vnodes)]
    ;; Patch positions that exist in both old and new
    (dotimes [i (min old-count new-count)]
      (patch-node! parent (nth old-nodes i) (nth new-vnodes i)))
    ;; Append new children
    (when (> new-count old-count)
      (doseq [vnode (subvec new-vnodes old-count)]
        (.appendChild parent (create-node vnode))))
    ;; Remove excess old children
    (when (> old-count new-count)
      (dotimes [_ (- old-count new-count)]
        (.removeChild parent (.-lastChild parent))))))

;;; ── Public API ────────────────────────────────────────────────────────────

(defn render!
  "Renders the hiccup that `view-fn` returns into `container`.
  The first render creates the nodes; later renders patch them in place."
  [^js container view-fn]
  (let [vnodes (flatten-children [(view-fn)])]
    (if (zero? (.-childNodes.length container))
      ;; First render: create from scratch
      (doseq [vnode vnodes]
        (.appendChild container (create-node vnode)))
      ;; Subsequent renders: reconcile
      (patch-children! container vnodes))))
