(ns clj-lazy-json.core
  (:require (clojure.java [io :as io]))
  (:use clj-lazy-json.cdx)
  (:import (org.codehaus.jackson JsonFactory JsonParser JsonToken)))

(def ^{:private true
       :tag JsonFactory}
  factory (JsonFactory.))

(defrecord Event [type contents])

(defn event [type & [contents]]
  (Event. type contents))

(defn fill-from-jackson
  "Filler function for use with fill-queue and Jackson."
  [^JsonParser parser fill]
  (letfn [(offer [type & [contents]]
            (fill (event type contents)))]
    (loop [token (.nextToken parser)]
      (when token
        (condp = token
          JsonToken/START_OBJECT  (offer :start-object)
          JsonToken/END_OBJECT    (offer :end-object)
          JsonToken/START_ARRAY   (offer :start-array)
          JsonToken/END_ARRAY     (offer :end-array)
          JsonToken/FIELD_NAME    (offer :field-name (.getCurrentName parser))
          JsonToken/NOT_AVAILABLE (offer :not-available)
          JsonToken/VALUE_EMBEDDED_OBJECT (offer :value-embedded-object) ; ?
          JsonToken/VALUE_FALSE   (offer :atom false (.getBooleanValue parser))
          JsonToken/VALUE_TRUE    (offer :atom true  (.getBooleanValue parser))
          JsonToken/VALUE_NULL    (offer :atom nil)
          JsonToken/VALUE_NUMBER_FLOAT (offer :atom (.getNumberValue parser))
          JsonToken/VALUE_NUMBER_INT   (offer :atom (.getNumberValue parser))
          JsonToken/VALUE_STRING       (offer :atom (.getText parser))
          (throw (RuntimeException.
                  (str "Missed a token type in fill-from-jackson: "
                       token))))
        (recur (.nextToken parser))))))

;;; adapted from clojure.data.xml
(defn lazy-source-seq
  "Returns a seq of parse events for the given source."
  ([source]         (lazy-source-seq source factory))
  ([source factory] (lazy-source-seq source factory Integer/MAX_VALUE))
  ([source factory queue-size]
     (fill-queue (partial fill-from-jackson (.createJsonParser factory source))
                 :queue-size queue-size)))

;;; adapted from clojure.data.xml
(defn event-tree
  "Returns a lazy tree of :object, :array and :atom nodes for the
  given seq of events."
  [events]
  (ffirst
   (seq-tree
    (fn [^Event event contents]
      (condp = (:type event)
        :start-object {:type :object
                       :entries (->> contents
                                     (partition 2)
                                     (map (fn [[k v]]
                                            (clojure.lang.MapEntry. k v))))}
        :start-array  {:type :array :entries contents}
        nil))
    (fn [^Event event]
      (or (= :end-object (:type event))
          (= :end-array  (:type event))))
    (fn [^Event event]
      {:type (:type event) :value (:contents event)})
    events)))

;;; adapted from clojure.data.xml
(defn lazy-parse
  "Obtains a Reader on source using clojure.java.io/reader and parses
  the result into a lazy JSON tree."
  [source]
  (-> source
      io/reader
      lazy-source-seq
      event-tree))

(defn parse-string
  "Parses the given string into a lazy JSON tree."
  [s]
  (-> s java.io.StringReader. lazy-parse))

(defn object?
  "Checks whether x looks like a lazy JSON object."
  [x]
  (and (map? x) (= :object (:type x))))

(defn array?
  "Checks whether x looks like a lazy JSON array."
  [x]
  (and (map? x) (= :array (:type x))))

(defn to-clj
  "Converts a lazy JSON tree to the natural Clojure representation."
  [json]
  (case (:type json)
    (:atom :field-name) (:value json)
    :array              (vec (map to-clj (:entries json)))
    :object             (into {} (map (fn [[k v]]
                                        [(to-clj k) (to-clj v)])
                                      (:entries json)))))

(defn consume-json
  "Calls (f json path), where json should be a lazy JSON tree
  representing a node in a JSON document being processed and path
  should describe its location in the document. See
  define-json-processor's docstring for a description of the path
  language.

  If (f json path) returns a truthy value g and json is a compound
  datum (a JSON object or array), then json's children will be
  recursively consumed using g (which is assumed to be a function). In
  any case, nil is returned."
  [f json path]
  (when-let [g (f json path)]
    (case (:type json)
      :object (dorun (map (fn [[k v]]
                            (consume-json g v (conj path (:value k))))
                          (:entries json)))
      :array  (dorun (map-indexed (fn [i v]
                                    (consume-json g v (conj path i)))
                                  (:entries json)))
      nil)))

(defn build-automaton
  "Used internally by process-lazy-json-tree and define-json-processor.
  See the docstring on the latter for a description of the supported
  options and the path language. See the docstring on consume-json for
  a description of the basic behaviour implemented."
  [opts paths-and-callbacks]
  (let [{:keys [all-matching cut-subtrees] :or {:all-matching true}} opts
        call-callbacks (if all-matching
                         (fn call-all-callbacks [a json path]
                           (when-let [callbacks (seq (keep #(get-in a [% ::here])
                                                           [(peek path) :* :**]))]
                             (doseq [callback callbacks]
                               (callback path (to-clj json)))
                             true))
                         (fn call-most-specific-callbacks [a json path]
                           (when-let [callback (some #(get-in a [% ::here])
                                                     [(peek path) :* :**])]
                             (callback path (to-clj json))
                             true)))]
    (loop [a {} pcs paths-and-callbacks]
      (if-let [[path callback] (first pcs)]
        (recur (assoc-in a (conj path ::here) callback)
               (next pcs))
        (letfn [(merge-pcs [left right]
                  (if (map? left)
                    (merge-with merge-pcs left right)
                    (fn [path json] (left path json) (right path json))))
                (automaton [a json path]
                  (when-not (and (call-callbacks a json path) cut-subtrees)
                    (partial automaton
                             (merge-with merge-pcs
                                         (get a (peek path))
                                         (get a :*)
                                         (when-let [starstar (get a :**)]
                                           (merge-with merge-pcs
                                                       starstar
                                                       {:** starstar}))))))]
          (partial automaton a))))))

(defn process-lazy-json-tree
  "Processes the given lazy JSON tree using a one-off processor. See
  the docstring on define-json-processor for more information.

  Example:

    (process-lazy-json-tree (-> \"{\\\"foo\\\": 1, \\\"bar\\\": 2}\"
                                parse-string)
                            {}
                            [:**] prn)"
  [lazy-json-tree opts & paths-and-callbacks]
  (consume-json (build-automaton opts (map vec (partition 2 paths-and-callbacks)))
                lazy-json-tree
                [:$]))

(defmacro define-json-processor
  "Defines a function of the given name and, optionally, with the
  given docstring, which takes a single argument, a lazy tree
  describing a JSON datum, and processes it lazily in accordance with
  the given specification.

  The optional opts? map currently supports the following keys:
    :all-matching (default: true): Call all callbacks registered for
      matching paths; if set to false, more specific callbacks take
      precedence. Specificity is currently determined by the last
      component of the path; any concrete value is more specific
      than :*, which is more specific than :**.
    :cut-subtrees (default: false): Do not process separately any
      nodes in subtrees starting at nodes for which callbacks have
      already been called.

  Paths are specified using the following language:
    :$ matches the root datum only;
    :* matches any datum in the current position in the path;
    :** matches any subpath;
    a literal string matches an object entry at that key;
    a literal number matches an array entry at that index.

  Callbacks receive two arguments: the complete path to the current
  node (starting at :$) and the clojurized representation of the
  node (as would be returned by clj-json or clojure.data.json).

  Example:

    (define-json-processor example-processor
      \"A simple JSON processor.\"
      [:$ \"foo\" 0] #(do (apply prn \"This is particularly interesting:\" %&))
      [:**] prn)

    (example-processor (-> \"{\\\"foo\\\": [1], \\\"bar\\\": [2]}\"
                           parse-string)
    ;; returns nil; printed output follows:
    [:$] {\"foo\" [1], \"bar\" [2]}
    [:$ \"foo\"] [1]
    \"This is particularly interesting:\" [:$ \"foo\" 0] 1
    [:$ \"bar\"] [2]
    [:$ \"bar\" 0] 2"
  [name docstring? opts? & paths-and-callbacks]
  (let [docstring (if (string? docstring?) docstring?)
        opts      (if docstring
                    (if (map? opts?) opts?)
                    (if (map? docstring?) docstring?))
        paths-and-callbacks (cond
                              (and docstring opts)
                              paths-and-callbacks
                              (or docstring opts)
                              (cons opts? paths-and-callbacks)
                              :else
                              (concat [docstring? opts?] paths-and-callbacks))
        paths-and-callbacks (vec (map vec (partition 2 paths-and-callbacks)))]
    `(let [automaton# (build-automaton ~opts ~paths-and-callbacks)]
       (defn ~name ~@(when docstring [docstring]) [~'lazy-json-tree]
         (consume-json automaton# ~'lazy-json-tree [:$])))))
