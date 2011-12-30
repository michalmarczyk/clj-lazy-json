;;; The following functions have been extracted from the
;;; clojure.data.xml library, see http://github.com/clojure/data.xml
;;; The only modification made to these definitions is the removal of
;;; the "private" marking (s/defn-/defn/g).

;;; clojure.data.xml code carries the following notice:
;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clj-lazy-json.cdx
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)
           (java.lang.ref WeakReference)))

(defn seq-tree
  "Takes a seq of events that logically represents
  a tree by each event being one of: enter-sub-tree event,
  exit-sub-tree event, or node event.

  Returns a lazy sequence whose first element is a sequence of
  sub-trees and whose remaining elements are events that are not
  siblings or descendants of the initial event.

  The given exit? function must return true for any exit-sub-tree
  event.  parent must be a function of two arguments: the first is an
  event, the second a sequence of nodes or subtrees that are children
  of the event.  parent must return nil or false if the event is not
  an enter-sub-tree event.  Any other return value will become
  a sub-tree of the output tree and should normally contain in some
  way the children passed as the second arg.  The node function is
  called with a single event arg on every event that is neither parent
  nor exit, and its return value will become a node of the output tree.

  (seq-tree #(when (= %1 :<) (vector %2)) #{:>} str
            [1 2 :< 3 :< 4 :> :> 5 :> 6])
  ;=> ((\"1\" \"2\" [(\"3\" [(\"4\")])] \"5\") 6)"
  [parent exit? node coll]
  (lazy-seq
    (when-let [[event] (seq coll)]
      (let [more (rest coll)]
        (if (exit? event)
          (cons nil more)
          (let [tree (seq-tree parent exit? node more)]
            (if-let [p (parent event (lazy-seq (first tree)))]
              (let [subtree (seq-tree parent exit? node (lazy-seq (rest tree)))]
                (cons (cons p (lazy-seq (first subtree)))
                      (lazy-seq (rest subtree))))
              (cons (cons (node event) (lazy-seq (first tree)))
                    (lazy-seq (rest tree))))))))))

(defn fill-queue
  "filler-func will be called in another thread with a single arg
  'fill'.  filler-func may call fill repeatedly with one arg each
  time which will be pushed onto a queue, blocking if needed until
  this is possible.  fill-queue will return a lazy seq of the values
  filler-func has pushed onto the queue, blocking if needed until each
  next element becomes available.  filler-func's return value is ignored."
  ([filler-func & optseq]
     (let [opts (apply array-map optseq)
           apoll (:alive-poll opts 1)
           q (LinkedBlockingQueue. (:queue-size opts 1))
           NIL (Object.)  ;nil sentinel since LBQ doesn't support nils
           weak-target (Object.)
           alive? (WeakReference. weak-target)
           fill (fn fill [x]
                  (if (.get alive?)
                    (if (.offer q (if (nil? x) NIL x) apoll TimeUnit/SECONDS)
                      x
                      (recur x))
                    (throw (Exception. "abandoned"))))
           f (future
               (try
                 (filler-func fill)
                 (finally
                  (.put q q)))          ;q itself is eos sentinel
               nil)]                    ; set future's value to nil
       ((fn drain []
          weak-target                 ; force closing over this object
          (lazy-seq
           (let [x (.take q)]
             (if (identical? x q)
               @f         ;will be nil, touch just to propagate errors
               (cons (if (identical? x NIL) nil x)
                     (drain))))))))))
