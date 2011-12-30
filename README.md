# clj-lazy-json

A [Jackson](http://jackson.codehaus.org/)-based lazy JSON parsing
library for Clojure.

Some code from the (EPL-licensed) `clojure.data.xml` library is being
reused here; see below for details.

Please note that at this early stage the API and even the scope of
this library is subject to change without notice.

## Usage

JSON documents can be parsed into a lazy tree representation by the
`lazy-parse` function, which expects as its argument anything
acceptable to `clojure.java.io/reader` (e.g. a `File`, `URI` or a
ready-made `Reader`). `parse-string` is a convenience wrapper for
dealing with JSON documents contained in strings.

The trees are meant to be processed by calling callback functions
registered for "paths"; for example

    (process-lazy-json-tree (parse-string "{\"foo\": 1, \"bar\": 2}")
                            {}
                            [:$ "foo"] #(apply prn "Foo!" %&)
                            [:$ "bar"] #(apply prn "Bar!" %&))

prints

    "Foo!" [:$ "foo"] 1
    "Bar!" [:$ "bar"] 2

and returns `nil`.

The `process-lazy-json-tree` function used in the example above
constructs a one-off JSON processor and uses it to process the given
lazy JSON tree. JSON processors which will be used repeatedly may be
defined using the `define-json-processor` macro.

Wildcards matching "any key/index" (`:*`) or "any subpath" (`:**`) are
supported in paths. The docstring of the `define-json-processor` macro
contains a description of the path language and the contract which
must be met by the callback functions.

The lazy JSON trees may be converted to the usual "natural" Clojure
representation using the `to-clj` function.

Note that no JSON emitting functionality is currently supported; this
is available in both `clojure.data.json` and `clj-json`.

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
