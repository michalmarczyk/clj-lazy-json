(ns clj-lazy-json.test.core
  (:require [clj-lazy-json.core :as core])
  (:use [clojure.test]))

(deftest basic-parse-test
  (let [json-str "{\"foo\": [1, 2], \"bar\": [null, true, false]}"
        m {"foo" [1 2]
           "bar" [nil true false]}]
    (is (= m (core/to-clj (core/parse-string json-str))))))

(deftest basic-queued-parse-test
  (let [json-str "{\"foo\": [1, 2], \"bar\": [null, true, false]}"
        m {"foo" [1 2]
           "bar" [nil true false]}]
    (is (= m (core/to-clj (core/queued-parse-string json-str))))))

(defn counting-string-reader
  "Returns a StringReader on s augmented with a counter of characters
  read accessible via deref."
  [s]
  (let [counter (atom 0)]
    (proxy [java.io.StringReader clojure.lang.IDeref] [s]
      (close [] (proxy-super close))
      (mark [n] (proxy-super mark n))
      (markSupported [this] (proxy-super markSupported))
      (read 
        ([]
           (swap! counter inc) 
           (proxy-super read))
        ([cbuf]
           (let [n (proxy-super read cbuf)]
             (when-not (== n -1)
               (swap! counter + n))
             n))
        ([cbuf off len]
           (let [n (proxy-super read cbuf off len)]
             (when-not (== n -1)
               (swap! counter + n))
             n)))
      (ready [] (proxy-super ready))
      (reset [] (proxy-super reset))
      (skip [n]
        (swap! counter + n)
        (proxy-super skip n))
      (deref [] @counter))))

(deftest test-laziness
  (let [test-json-string-1 (apply str "{" (concat (for [i (range 100000)]
                                                    (str "\"foo" i "\": " i ", "))
                                                  ["\"bar\": null}"]))
        test-json-string (apply str "{" (concat (for [i (range 100)]
                                                  (str "\"quux" i "\": "
                                                       test-json-string-1 ", "))
                                                ["\"baz\": null}"]))
        test-json-reader (counting-string-reader test-json-string)
        test-json-reader-for-queued-parser (counting-string-reader
                                            test-json-string)]
    (are [p] (thrown-with-msg? RuntimeException #"^stop here$"
               (core/process-lazy-json-tree
                p
                {}
                [:$ "quux2" "foo3"]
                (fn [_ _] (throw (RuntimeException. "stop here")))))
         (core/lazy-parse test-json-reader)
         (core/queued-parse test-json-reader-for-queued-parser))
    ;; sanity check / silly typo avoidance:
    (is (< (* 100 (count test-json-string-1)) (count test-json-string)))
    (are [r] (<= @r (* 4 (count test-json-string-1)))
         test-json-reader
         test-json-reader-for-queued-parser)))

(def a (atom 0))

(core/define-json-processor accumulating-processor
  [:$ :*] (fn [_ n] (swap! a + n)))

(deftest test-accumulating-processor
  (accumulating-processor (core/parse-string "{\"foo\": 1, \"bar\": 2}"))
  (is (== @a 3)))

(def b (atom 0))

(core/define-json-processor all-matching-accumulating-processor
  [:$ :*] (fn [_ n] (swap! b + n))
  [:$ "foo"] (fn [_ _] (swap! b inc)))

(deftest test-all-matching-accumulating-processor
  (all-matching-accumulating-processor
   (core/parse-string "{\"foo\": 1, \"bar\": 2}"))
  (is (== @b 4)))
