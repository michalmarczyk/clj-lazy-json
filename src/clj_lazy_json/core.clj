(ns clj-lazy-json.core
  (:require (clojure.java [io :as io]))
  (:use clj-lazy-json.cdx)
  (:import (org.codehaus.jackson JsonFactory JsonParser JsonToken)))

(def ^{:private true
       :tag JsonFactory}
  factory (JsonFactory.))

(defrecord Event [type contents])

(defn ^:private event [type & [contents]]
  (Event. type contents))

(defn ^:private fill-from-jackson
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

(defn parse
  "Returns a seq of parse events for the given source."
  ([source] (parse source factory))
  ([source factory]
     (let [parser (.createJsonParser ^JsonFactory factory (io/reader source))

           token->event
           (fn token->event [token]
             (condp = token
               JsonToken/START_OBJECT  (event :start-object)
               JsonToken/END_OBJECT    (event :end-object)
               JsonToken/START_ARRAY   (event :start-array)
               JsonToken/END_ARRAY     (event :end-array)
               JsonToken/FIELD_NAME    (event :field-name (.getCurrentName parser))
               JsonToken/NOT_AVAILABLE (event :not-available)
               JsonToken/VALUE_EMBEDDED_OBJECT (event :value-embedded-object) ; ?
               JsonToken/VALUE_FALSE   (event :atom false)
               JsonToken/VALUE_TRUE    (event :atom true)
               JsonToken/VALUE_NULL    (event :atom nil)
               JsonToken/VALUE_NUMBER_FLOAT (event :atom (.getNumberValue parser))
               JsonToken/VALUE_NUMBER_INT   (event :atom (.getNumberValue parser))
               JsonToken/VALUE_STRING       (event :atom (.getText parser))
               (throw (RuntimeException.
                       (str "Missed a token type in lazy-source-seq: "
                            token)))))

           token-seq
           (fn token-seq []
             (lazy-seq
              (when-let [token (.nextToken parser)]
                (cons (token->event token)
                      (token-seq)))))]

       (token-seq))))

;;; adapted from clojure.data.xml
(defn ^:private event-tree
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
      {:type (:type event) :contents (:contents event)})
    events)))

(defn ^:private skip-object [events lvl]
  (lazy-seq
    (if-not (zero? lvl)
      (when-first [e events]
        (case (:type e)
          :end-object (cons e (skip-object (next events) (dec lvl)))
          :start-object (cons e (skip-object (next events) (inc lvl)))
          (cons e (skip-object (next events) lvl)))))))

(defn ^:private skip-array [events lvl]
  (lazy-seq
    (if-not (zero? lvl)
      (when-first [e events]
        (case (:type e)
          :end-array (cons e (skip-array (next events) (dec lvl)))
          :start-array (cons e (skip-array (next events) (inc lvl)))
          (cons e (skip-array (next events) lvl)))))))

(defn ^:private to-tree [events]
  (when-first [e events]
    (case (:type e)
      :start-object (event-tree (cons e (skip-object (next events) 1)))
      :start-array  (event-tree (cons e (skip-array (next events) 1)))
      e)))

(defn parse-string
  "Parses the JSON document contained in the string s into a seq of parse events."
  [s]
  (-> s java.io.StringReader. parse))

(defn ^:private to-clj
  "Converts a lazy JSON tree to the natural Clojure representation."
  [json]
  (case (:type json)
    (:atom :field-name) (:contents json)
    :array              (vec (map to-clj (:entries json)))
    :object             (into {} (map (fn [[k v]]
                                        [(to-clj k) (to-clj v)])
                                      (:entries json)))))

(defn build-automaton
  "Used internally by process-json and define-json-processor.
  See the docstring on the latter for a description of the supported
  options and the path language. See the docstring on consume-json for
  a description of the basic behaviour implemented."
  [opts paths-and-callbacks]
  (loop [a {} pcs paths-and-callbacks]
    (if-let [[path callback] (first pcs)]
      (recur (assoc-in a (conj path ::here) callback)
             (next pcs))
      a)))

(defn ^:private step-automaton [automaton path]
  (letfn [(merge-pcs [left right]
            (if (map? left)
              (merge-with merge-pcs left right)
              (fn [path json] (left path json) (right path json))))]
    (merge-with merge-pcs
                (get automaton (peek path))
                (get automaton :*)
                (when-let [starstar (get automaton :**)]
                  (merge-with merge-pcs
                              starstar
                              {:** starstar})))))

(defn ^:private call-callbacks [automaton path events]
  (when-let [callback (get automaton ::here)]
    (let [datum (to-clj (to-tree events))]
      (callback path datum))
    true))

(defn consume-json
  "Used internally by process-json and define-json-processor."
  [automaton events path]
  (letfn [(go [as path events]
            (when-first [e events]
              (let [path (if (number? (peek path))
                           (conj (pop path) (inc (peek path)))
                           path)]
                (case (:type e)
                  (:end-array :end-object)
                  (recur (pop as) (pop path) (next events))

                  :start-array
                  (do (call-callbacks (peek as) path events)
                      (let [new-path (conj path -1)
                            new-a    (step-automaton (peek as) new-path)]
                        (recur (conj as new-a) new-path (next events))))

                  :start-object
                  (do (call-callbacks (peek as) path events)
                      (recur (conj as nil) (conj path nil) (next events)))

                  :field-name
                  (let [new-path (conj (pop path) (:contents e))
                        new-a    (step-automaton (peek (pop as)) new-path)]
                    (recur (conj (pop as) new-a) new-path (next events)))

                  :atom
                  (do (call-callbacks (peek as) path events)
                      (recur as path (next events)))))))]
    (go [(step-automaton automaton path)] path events)))

(defn process-json
  "Constructs a one-off JSON processor and uses it to process parsed-json.
  See docstring on define-json-processor for processor definition
  syntax and supported options."
  [parsed-json opts & paths-and-callbacks]
  (consume-json (build-automaton opts (map vec (partition 2 paths-and-callbacks)))
                parsed-json
                [:$]))

(defmacro define-json-processor
  "Defines a function of the given name and, optionally, with the
  given docstring, which takes a single argument, a seq of parse
  events describing a JSON datum (as output by the parse and
  parse-string functions), and processes it lazily in accordance with
  the given specification.

  Options are currently ignored.

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
    [:$ \"foo\" 0] 1
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
       (defn ~name ~@(when docstring [docstring]) [~'parsed-json]
         (consume-json automaton# ~'parsed-json [:$])))))
