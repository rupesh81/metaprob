(ns dontknow.builtin
  (:refer-clojure :only [defmacro])
  (:require [dontknow.library]))

                         ;; declare letfn let fn assert for
                         ;; ;; We don't need the following any more; delete
                         ;; ;; them anon
                         ;; cond case
                         ;; dosync
                         ;; count map? get assoc zipmap
                         ;; with-meta meta apply ref ref-set deref
                         ;; type instance? ns-resolve
                         ;; symbol symbol? string?
                         ;; cons conj list? concat mapcat list empty? list? seq?
                         ;; vec vector? nth count contains?
                         ;; str boolean?
                         ;; print newline format
                         ;; + - * / = < > <= >= number?
                         ;; rand

; This module is intended for import by metaprob code.
; To access any of this functionality from clojure, please import 
; library.clj.

; --------------------
; The following are special forms and are defined in *every* namespace
; independent of anything you can do:
;
;  def do if

; Re-export clojure macros

(defmacro declare [& rest]
  `(clojure.core/declare ~@rest))

(defmacro and [& rest]
  `(clojure.core/and ~@rest))

(defmacro or [& rest]
  `(clojure.core/or ~@rest))

; This isn't part of metaprob but boy is it useful

(defmacro let [& rest]
  `(clojure.core/let ~@rest))

; Re-export library macros

(defmacro define [& rest]
  `(dontknow.library/define ~@rest))

(defmacro program [& rest]
  `(dontknow.library/program ~@rest))

(defmacro block [& rest]
  `(dontknow.library/block ~@rest))

; Re-expore library functions

(def not dontknow.library/mp-not)

(def eq dontknow.library/eq)
(def neq dontknow.library/neq)
(def gt dontknow.library/gt)
(def gte dontknow.library/gte)
(def lt dontknow.library/lt)
(def lte dontknow.library/lte)

(def add dontknow.library/add)
(def sub dontknow.library/sub)
(def mul dontknow.library/mul)
(def div dontknow.library/div)
; etc. etc.

(def pair dontknow.library/pair)
(def list dontknow.library/mp-list)
(def first dontknow.library/mp-first)
(def rest dontknow.library/mp-rest)
(def last dontknow.library/mp-last)
(def map dontknow.library/mp-map)
(def range dontknow.library/mp-range)

(def pprint dontknow.library/mp-pprint)

(def length dontknow.library/length)
(def mk_nil dontknow.library/mk_nil)
(def list_to_array dontknow.library/list_to_array)

(def trace_get dontknow.library/trace_get)
(def trace_has dontknow.library/trace_has)
(def trace_set dontknow.library/trace_set)
(def trace_set_at dontknow.library/trace_set_at)
(def trace_set_subtrace_at dontknow.library/trace_set_subtrace_at)
(def trace_has_key dontknow.library/trace_has_key)
(def trace_subkeys dontknow.library/trace_subkeys)
(def lookup dontknow.library/lookup)

(def make_env dontknow.library/make_env)
(def match_bind dontknow.library/match_bind)
(def env_lookup dontknow.library/env_lookup)
(def interpret dontknow.library/interpret)
(def interpret_prim dontknow.library/interpret_prim)
(def error dontknow.library/error)

(def flip dontknow.library/flip)
(def uniform dontknow.library/uniform)
(def capture_tag_address dontknow.library/capture_tag_address)
(def resolve_tag_address dontknow.library/resolve_tag_address)
(def name_for_definiens dontknow.library/name_for_definiens)