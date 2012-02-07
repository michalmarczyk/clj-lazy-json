# clj-lazy-json

A [Jackson](http://jackson.codehaus.org/)-based lazy JSON parsing
library for Clojure.

Some code from the (EPL-licensed) `clojure.data.xml` library is being
reused here; see below for details.

Please note that at this early stage the API and even the scope of
this library is subject to change without notice.

## Usage

### Overview

`clj-lazy-json` defines a lazy tree representation for JSON documents
and a method of processing JSON documents so represented. The latter
is based on a query / path specification language for matching nodes
in a JSON document (vaguely resembling -- simplified -- XQuery, CSS
selectors and the like); a `define-json-processor` macro allows one to
package a handful of paths together with appropriate callbacks in a
regular Clojure function which can then be used to process JSON
documents.

JSON text can be parsed into the lazy tree form by the `lazy-parse`
function, which can be called on anything acceptable to
`clojure.java.io/reader` (e.g. a `File`, `URI` or a ready-made
`Reader`). `parse-string` is a convenience wrapper for dealing with
JSON documents contained in strings. These functions, as well as the
underlying `lazy-source-seq` function, operate on simple Clojure lazy
seqs. Queue-backed variants using code from `clojure.data.xml` are
also available, see `queued-parse`, `queud-parse-string` and
`queued-source-seq`.

During development, rather than defining named JSON processing
functions, it may be convenient to use the `process-lazy-json-tree`
function; for example

    (process-lazy-json-tree (parse-string "{\"foo\": 1, \"bar\": 2}")
                            {}
                            [:$ "foo"] #(apply prn "Foo!" %&)
                            [:$ "bar"] #(apply prn "Bar!" %&))

prints

    "Foo!" [:$ "foo"] 1
    "Bar!" [:$ "bar"] 2

and returns `nil`. To achieve the same effect with a named processor,
one would say

    (define-json-processor foo-bar-processor
      [:$ "foo"] #(apply prn "Foo!" %&)
      [:$ "bar"] #(apply prn "Bar!" %&))

    (foo-bar-processor (parse-string "{\"foo\": 1, \"bar\": 2}"))

Wildcards matching "any key/index" (`:*`) or "any subpath" (`:**`) are
supported in paths. The docstring of the `define-json-processor` macro
contains a description of the path language and the contract which
must be met by the callback functions.

The lazy JSON trees may be converted to the usual "natural" Clojure
representation using the `to-clj` function.

Note that no JSON emitting functionality is currently supported; this
is available in both `clojure.data.json` and `clj-json`.

### Example

Let's have a look at an example. First, a simple JSON document:

    (def test-json
      "{\"foo\": [{\"bar\": 1}, {\"foo\": {\"quux\": {\"bar\": 2}}}],
        \"bar\": [3]}")

Suppose we want to call some function with the values attached to bars
below at least one foo. We'll use the following callback function:

    (defn print-value-callback [_ v] (prn v))

To demonstrate the use of a callback's first argument, we'll also call
a function to print out its value at a different path. This function
is defined inline in the parser specification below, just to show it's
possible.

    (define-json-processor example-processor
      "Print out the values attached to bars below at least one foo."
      [:** "foo" :** "bar"] print-value-callback
      [:$ "bar" :*] (fn print-path [path _] (prn path)))

Here `example-processor` is a regular Clojure function. It takes one
argument named `lazy-json-tree` and has the specified docstring
attached. To test it out on our example document, one would say

    (example-processor (parse-string test-json))
    1
    2
    2
    [:$ "bar" 0]
    ; nil

The `2` is printed twice, because its position in the tree matches the
first path in two ways (see below for details).

The DSL used to define `example-processor` breaks down as follows:

    [:** "foo" :** "bar"] print-value
    ; <----- path ----->  <callback>
    ; ^- :** -- skip any (possibly empty) subpath
    ;    ^- "foo" -- expect to see an object; descend into the value
    ;                attached to key "foo"
    ;          ^- :** -- skip any subpath
    ;              ^- "bar" -- descend at key "bar"; this is the end
    ;                          of the path spec, so call the attached
    ;                          callback -- print-value -- with the
    ;                          current node

    [:$ "bar" :*] (fn print-path [path _] (prn path))
    ; ^- match document root
    ;   ^- expect previously matched element (= root) to be an object;
    ;      follow key "bar"
    ;         ^- expect to see an object or an array; call the callback
    ;            for all children (:* matches any single step in the path)

The callbacks receive two arguments: the exact path to the current
node in the JSON document, which is a vector of `:$` possibly followed
by strings (object keys) and numbers (array indices), and a standard
"Clojurized" representation of the node's value (with objects
converted to maps and arrays to vectors).

## Use of `clojure.data.xml` code

The lazy trees used here are constructed using two functions from
`clojure.data.xml`, `fill-queue` and `seq-tree`, copied here because
they are marked private in their original namespace of residence.
`clojure.data.xml` code carries the following notice:

    ;   Copyright (c) Rich Hickey. All rights reserved.
    ;   The use and distribution terms for this software are covered by the
    ;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
    ;   which can be found in the file epl-v10.html at the root of this distribution.
    ;   By using this software in any fashion, you are agreeing to be bound by
    ;   the terms of this license.
    ;   You must not remove this notice, or any other, from this software.

This notice is also reproduced in the `src/clj_lazy_json/cdx.clj` file
containing this code. See also the file `epl-v10.html` at the root of
the present distribution.

## Fablo

This work was sponsored by Fablo (http://fablo.eu/). Fablo provides a
set of tools for building modern e-commerce storefronts. Tools include
a search engine, product and search result navigation, accelerators,
personalized recommendations, and real-time statistics and analytics.

## Licence

Copyright (C) 2011 Michał Marczyk

Distributed under the Eclipse Public License, the same as Clojure.
