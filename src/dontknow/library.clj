(ns dontknow.library
  (:require [clojure.string]
            [clojure.pprint :as pp]
            [dontknow.trie :refer :all]))

;; This module is intended for import by clojure code.
;; For metaprob builtins, please import the metaprob namespace and
;; suppress the impulse to import clojure.core.

(declare from-clojure)

(defmacro define
  "like def, but allows patterns"
  [pattern rhs]

  (letfn [(var-for-pattern [pat]
            (if (symbol? pat)
              pat
              (symbol (clojure.string/join "|"
                                           (map var-for-pattern pat)))))

          ;; Returns a list [[var val] ...]
          ;; to be turned into, say, (block (define var val) ...)
          ;; or into (let [var val ...] ...)

          (explode-pattern [pattern rhs]
            (if (symbol? pattern)
              (list (list pattern rhs))
              (let [var (var-for-pattern pattern)]
                (cons (list var rhs)
                      (mapcat (fn [subpattern i]
                                (if (= subpattern '_)
                                  (list)
                                  (explode-pattern subpattern `(nth ~var ~i))))
                              pattern
                              (range (count pattern)))))))]

    `(do ~@(map (fn [[var val]] `(def ~var ~val))
                (explode-pattern pattern rhs)))))

(defn make-program [fun name params body ns]
  (let [exp (from-clojure `(program ~params ~@body))
        env ns]
    (with-meta fun {:name name
                    :trace (trie-from-map {"name" (new-trie exp)
                                           "source" exp
                                           "environment" (new-trie env)}
                                          "prob prog")})))

(defmacro named-program [name params & body]
  `(make-program (fn ~params (block ~@body))
                 '~name
                 '~params
                 '~body
                 ;; *ns* will be ok at top level as a file is loaded,
                 ;; but will be nonsense at other times.  Fix somehow.
                 ;; (should be lexical, not dynamic.)
                 *ns*))

(defmacro program
  "like fn, but for metaprob programs"
  [params & body]
  `(named-program a-program ~params ~@body))

(defmacro block
  "like do, but for metaprob - supports local definitions"
  [& forms]
  (letfn [(definition? [form]
            (and (seq? form)
                 (= (first form) 'def)))
          (definition-pattern [form]
            (second form))
          (definition-rhs [form]
            (nth form 2))
          (program-definition? [form]
            (and (definition? form)
                 (symbol? (definition-pattern form))
                 (let [rhs (definition-rhs form)]
                   (and (list? rhs)
                        (= (first rhs) 'program)))))
          (qons [x y]
            (if (list? y)
              (conj y x)
              (conj (concat (list) y) x)))
          (process-definition [form]
            (assert program-definition? form)
            (let [rhs (definition-rhs form)       ;a program-expression
                  prog-pattern (definition-pattern rhs)
                  prog-body (rest (rest rhs))]
              (qons (definition-pattern form)
                    (qons prog-pattern
                          prog-body))))

          (block-to-body [forms]
            (if (empty? forms)
              '()
              (let [more (block-to-body (rest forms))]    ; list of forms
                (if (definition? (first forms))
                  (let [pattern (definition-pattern (first forms))
                        rhs (definition-rhs (first forms))]
                    ;; A definition must not be last expression in a block
                    (if (empty? (rest forms))
                      (print (format "** Warning: Definition of %s occurs at end of block\n"
                                     pattern)))
                    (if (program-definition? (first forms))
                      (let [spec (process-definition (first forms))
                            more1 (first more)]
                        (if (and (list? more1)
                                 (= (first more1) 'letfn))
                          (do (assert (empty? (rest more)))
                              ;; more1 = (letfn [...] ...)
                              ;;    (letfn [...] & body)
                              ;; => (letfn [(name pattern & prog-body) ...] & body)
                              (let [[_ specs & body] more1]
                                (list             ;Single form
                                 (qons 'letfn
                                       (qons (vec (cons spec specs))
                                             body)))))
                          ;; more1 = something else
                          (list                   ;Single form
                           (qons 'letfn
                                 (qons [spec]
                                       more)))))
                      ;; Definition, but not of a function
                      ;; (first forms) has the form (def pattern rhs)
                      (let [more1 (first more)]
                        (if (and (list? more1)
                                 (= (first more1) 'let))
                          ;; Combine two lets into one
                          (do (assert (empty? (rest more)))
                              (let [[_ specs & body] more1]
                                (list             ;Single form
                                 (qons 'let
                                       (qons (vec (cons pattern (cons rhs specs)))
                                             body)))))
                          (list                   ;Single form
                           (qons 'let
                                 (qons [pattern rhs]
                                       more)))))))
                  ;; Not a definition
                  (qons (first forms) more)))))

          (formlist-to-form [forms]
            (assert (seq? forms))
            (if (empty? forms)
              'nil
              (if (empty? (rest forms))
                (first forms)
                (if (list? forms)
                  (qons 'do forms)
                  (qons 'do (concat (list) forms))))))]
    (formlist-to-form (block-to-body forms))))

; Similar to sp_to_prob_prog
;  return make_prob_prog(
;    name=name,
;    simulator=sp.simulate,
;    interpreter=python_interpreter_prob_prog(sp.py_propose),
;    tracer=python_tracer_prob_prog(sp.py_propose),
;    proposer=python_proposer_prob_prog(sp.py_propose),
;    ptc=python_ptc_prob_prog(sp.py_propose))

(defn make-primitive [name fun]
  (letfn [(simulate [args]          ;sp.simulate
            (apply fun args))
          (py-propose [args _intervention _target _output] ;sp.py_propose
            [(apply fun args) 0])]
    (with-meta fun {:trace (trie-from-map {"name" (new-trie name)
                                           "executable" (new-trie simulate)
                                           "custom_interpreter" (new-trie py-propose)
                                           "custom_choice_trace" (new-trie py-propose)
                                           "custom_proposer" (new-trie py-propose)
                                           "custom_choice_tracing_proposer" (new-trie py-propose)}
                                          "prob prog")})))

; The aux-name is logically unnecessary but helps with debugging.

(defmacro define-primitive [lib-name mp-name params & body]
  (let [aux-name (symbol (str lib-name '|primitive))]
    `(do (declare ~lib-name)
         (defn ~aux-name ~params ~@body)
         (def ~lib-name
           (make-primitive '~mp-name
                           ~aux-name)))))

; If an object has a :trace meta-property, then return that
; meta-property value.  Otherwise, just return the object.

(defn tracify [x]
  (if (trace? x)
    x
    (let [m (meta x)]
      (if (map? m)
        (if (contains? m :trace)
          (get m :trace)
          x)
        x))))

;; ----------------------------------------------------------------------
;; Builtins (defined in python in original-metaprob)

;; The assert macro in clojure is much nicer, since (1) it can catch
;; exceptions in the evaluation of its subforms, (2) it can show you
;; the source code for the subforms.

(define-primitive mp-assert assert [condition complaint]
  (assert condition complaint))

; Used in prelude.vnts:
;   is_metaprob_array  - how to define?

(def mp-not (make-primitive 'not not))
(def eq (make-primitive 'eq =))

(define-primitive neq neq [x y] (not (= x y)))

(def gt (make-primitive 'gt >))
(def gte (make-primitive 'gte >=))
(def lte (make-primitive 'lte <=))
(def lt (make-primitive 'lt <))

(declare append)

(define-primitive add add [x y]
  (if (number? x)
    (+ x y)
    (if (trace? x)
      (append x y)
      (if (seqable? x)
        (concat x y)
        (assert false ["invalid argument for add" x])))))

(def sub (make-primitive 'sub -))
(def mul (make-primitive 'mul *))
(def div (make-primitive 'div /))

(define-primitive mk_nil mk_nil [] (new-trie))                 ; {{ }}

(declare is_pair mp-first mp-rest)

; TBD: permit tuple here?

(defn addrify [addr]
  (if (trace? addr)
    (if (is_pair addr)
      (cons (mp-first addr)
            (addrify (mp-rest addr)))
      (list))
    addr))

(define-primitive trace_get trace_get [tr] (value (tracify tr)))        ; *e
(define-primitive trace_has trace_has [tr] (has-value? (tracify tr)))
(define-primitive trace_set trace_set [tr val]            ; e[e] := e
  (set-value! (tracify tr) val))
(define-primitive trace_set_at trace_set_at [tr addr val]
  (set-value-at! (tracify tr) (addrify addr) val))
(define-primitive trace_set_subtrace_at trace_set_subtrace_at [tr addr sub]
  (set-subtrie-at! (tracify tr) (addrify addr) sub))
(define-primitive trace_has_key trace_has_key [tr key] (has-subtrie? (tracify tr) key))
(define-primitive trace_subkeys trace_subkeys [tr] (trie-keys (tracify tr)))
(define-primitive lookup lookup [tr addr]
  ;; addr might be a metaprobe seq instead of a clojure seq.
  (subtrace-at (tracify tr) (addrify addr)))  ; e[e]


;; Called 'apply' in lisp
;; Same as in metacirc-stub.vnts

(define-primitive interpret interpret [proposer inputs intervention-trace]
  ;; proposer is (fn [args _intervention _target _output] ...)
  (if (has-value? intervention-trace)
    (value intervention-trace)
    ;; Discard weight?
    (proposer (subtrie-values-to-seq inputs)
              intervention-trace
              (new-trie)
              (new-trie))))
  

;; def interpret_prim(f, args, intervention_trace):
;;   if intervention_trace.has():
;;     return intervention_trace.get()
;;   else:
;;     return f(metaprob_collection_to_python_list(args))

(define-primitive interpret_prim interpret_prim [simulate inputs intervention-trace]
  (if (has-value? intervention-trace)
    (value intervention-trace)
    (simulate inputs)))


(define-primitive mp-pprint pprint [x]
  ;; x is a trie.  need to prettyprint it somehow.
  (print (format "[prettyprint %s]\n" x)))

; Other builtins

(define-primitive flip flip [weight] (<= (rand) weight))

(define-primitive uniform uniform [a b] (+ (rand (- b a)) a))

;; TBD (needed by prelude):
;; trace_sites uniform_categorical uniform_continuous

(define-primitive resolve_tag_address resolve_tag_address [stuff]
  stuff)

(define-primitive name_for_definiens name-for-definiens [pattern]
  (if (symbol? pattern)
    (if (= pattern '_)
      'definiens
      pattern)
    'definiens))

;; pair - not defined in prelude

(def rest-marker "rest")

(define-primitive pair pair [thing mp-list]
  (trie-from-map {rest-marker mp-list} thing))

;; list_to_array - convert metaprob list to metaprob array (tuple)
;; TBD: rewrite functionally

;; auxiliary - is a trace a metaprob representation of an empty vector
;; or empty list?

(defn empty-trace? [mp-seq]
  (= (trie-count mp-seq) 0))

(define-primitive list_to_array list_to_array [mp-list]
  (let [arr (mk_nil)]
    (letfn [(r [mp-list n]
              (if (empty-trace? mp-list)
                arr
                (do (set-value-at! arr n (first mp-list))
                    (r (rest mp-list)
                       (+ n 1)))))]
      (r mp-list 0))))

;; list - builtin

(defn seq-to-metaprob-list [things]
  (if (empty? things)
    (mk_nil)
    (pair (first things) (seq-to-metaprob-list (rest things)))))

(define-primitive mp-list list [& things]
  (seq-to-metaprob-list things))

;; ----------------------------------------------------------------------
;; Defined in original prelude (if they are here, then there should be
;; some good reason not to use the prelude version)

;; first - overrides original prelude (performance + generalization)

(define-primitive mp-first first [mp-list]
  (if (trace? mp-list)
    (value mp-list)
    (first mp-list)))

;; rest - overrides original prelude (performance + generalization)

(define-primitive mp-rest rest [mp-list]
  (if (trace? mp-list)
    (subtrie mp-list rest-marker)
    (rest mp-list)))

;; is_pair - overrides original prelude (performance + generalization)

(define-primitive is_pair is_pair [x]
  (and (trace? x)
       (has-value? x)
       (has-subtrie? x rest-marker)))

;; length - overrides original prelude (performance + generalization)

(define-primitive length length [x]
  (if (trace? x)
    (if (is_pair x)
      (letfn [(scan [x]
                (if (is_pair x)
                  (+ 1 (scan (mp-rest x)))
                  0))]
        (scan x))
      (trie-count x))
    (count x)))

;; drop - use prelude version?

;; last - overrides original prelude (performance + generalization)

(defn mp-list-last [mp-list]
  (if (is_pair mp-list)
    (let [more (mp-rest mp-list)]
      (if (not (is_pair more))
        (mp-first mp-list)
        (mp-list-last more)))
    mp-list))

(define-primitive mp-last last [mp-list]
  (if (trace? mp-list)
    (mp-list-last mp-list)
    (last mp-list)))

;; nth - overrides original prelude (performance + generalization)

(defn mp-list-nth [mp-list i]
  (if (= i 0)
    (first mp-list)
    (mp-list-nth (rest mp-list) i)))

(define-primitive mp-nth nth [thing i]
  (if (trace? thing)
    (if (is_pair thing)
      (mp-list-nth thing i)
      (value (subtrie thing i)))
    (nth thing i)))

;; prelude has: reverse, propose1, iterate, replicate, repeat

;; range - overrides original prelude (performance + generalization)

(defn _range [n k]
  (if (gte k n) (mk_nil) (pair k (_range n (add k 1)))))

(define-primitive mp-range range [n]
  (_range n 0))

;; map - overrides original prelude - BUT DON'T DO THIS.

;; Attempt to make type of result be the same as type of input.
;; --> We'll want to use the version from the original prelude so that
;; the traces can propagate through to calls to the function.

(define-primitive mp-map map [mp-fn mp-seq]
  ;; Do something - need to thread the trace through.
  (let [mp-seq (tracify mp-seq)]
    (if (trace? mp-seq)
      (if (empty-trace? mp-seq)
        mp-seq
        (if (is_pair mp-seq)
          (letfn [(maplist [l]
                    (assert (trace? l) l)
                    (if (empty-trace? l)
                      l
                      (pair (mp-fn (mp-first l))
                            (maplist (mp-rest l)))))]
            (maplist mp-seq))
          ;; tbd: do this with zipmap instead of recursion
          (letfn [(maptup [i]
                    (if (has-subtrie? mp-seq i)
                      (assoc (maptup (+ i 1))
                             i
                             (new-trie (mp-fn (value (subtrie mp-seq i)))))
                      {}))]
            (trie-from-map (maptup 0) "tuple"))))
      (map mp-fn mp-seq))))

;; original prelude has: imap, zipmap, for_each, filter

;; append - overrides original prelude (performance)
;; This is only for lists, not for tuples.

(define-primitive append append [x y]
  (if (is_pair x)
    (pair (mp-first x) (append (mp-rest x) y))
    y))

;; prelude has: trace_of lookup_chain lookup_chain_with_exactly 

;; What about sp = tracing_proposer_to_prob_prog in prelude (!!) - do
;; we need it, how to define, etc.?

;; original prelude has: proposer_of factor apply_with_address

;; prelude has: trace_of lookup_chain lookup_chain_with_exactly 

;; error - overrides original prelude (???)

(define-primitive error error [x]
  (assert false x))                     ;from prelude.vnts

;; capture_tag_address - overrides original prelude - but definition is the same.
;; Compare builtin resolve_tag_address, defined above.

(define-primitive capture_tag_address capture_tag_address [& stuff]
  stuff)

;; Environments

;; env_lookup - overrides original prelude

(define-primitive env_lookup env_lookup [env name]
  (assert (string? name) [name (type name)])
  (if (instance? clojure.lang.Namespace env)
    (deref (ns-resolve env (symbol name)))
    (do (assert (map? env) env)
        (or (get (deref (first env)) name)
            (env_lookup (rest env) name)))))

;; make_env - overrides original prelude

(define-primitive make_env make_env [parent]
  (cons (ref {}) parent))

;; match_bind - overrides original prelude

(define-primitive match_bind match_bind [pattern inputs env]
  (dosync
   (letfn [(mb [pattern inputs]
             (if (not (seq? pattern))
               (if (not (= pattern '_))
                 ;; in transaction???
                 (ref-set (first env) pattern inputs))
               (if (not (empty? pattern))
                 (do (mb (first pattern) (first inputs))
                     (mb (rest pattern) (rest inputs))))))]
     (mb pattern inputs))
   env))

;; ---- end of prelude ----

;; does this get used? I don't think so.

(defn seq-to-metaprob-tuple [things]
  (trie-from-seq (map new-trie things) "tuple"))

; -----------------------------------------------------------------------------

; Convert a clojure expression to a metaprob parse tree / trie.
; Assumes that the input was generated by the to_clojure converter.

(defn from-clojure-seq [seq val]
  (trie-from-seq (map from-clojure seq) val))

(defn from-clojure-program [exp]
  (let [[_ pattern & body] exp]
    (let [body-exp (if (= (count body) 1)
                     (first body)
                     (cons 'block body))]
      (trie-from-map {"pattern" (from-clojure pattern)
                      "body" (from-clojure body-exp)}
                     "program"))))

(defn from-clojure-if [exp]
  (let [[_ pred thn els] exp]
    (trie-from-map {"predicate" (from-clojure pred)
                    "then" (from-clojure thn)
                    "else" (from-clojure els)}
                   "if")))

(defn from-clojure-block [exp]
  (from-clojure-seq (rest exp) "block"))

(defn from-clojure-with-address [exp]
  (let [[_ tag ex] exp]
    (trie-from-map {"tag" (from-clojure tag)
                    "expression" (from-clojure ex)}
                   "with_address")))

; This doesn't handle _ properly.  Fix later.

(defn from-clojure-definition [exp]
  (let [[_ pattern rhs] exp
        key (if (symbol? pattern) (str pattern) "definiens")]
    (trie-from-map {"pattern" (from-clojure pattern)
                    key (from-clojure rhs)}
                   "definition")))

(defn from-clojure-application [exp]
  (from-clojure-seq exp "application"))

(defn from-clojure-tuple [exp]
  (from-clojure-seq exp "tuple"))

(defn from-clojure-1 [exp]
  (cond (vector? exp) (from-clojure-tuple exp)
        ;; I don't know why this is sometimes a non-list seq.
        (seq? exp) (case (first exp)
                     program (from-clojure-program exp)
                     if (from-clojure-if exp)
                     block (from-clojure-block exp)
                     splice (trie-from-map {"expression" (from-clojure exp)} "splice")
                     unquote (trie-from-map {"expression" (from-clojure exp)} "unquote")
                     with-address (from-clojure-with-address exp)
                     define (from-clojure-definition exp)
                     ;; else
                     (from-clojure-application exp))
        (= exp 'this) (trie-from-map {} "this")
        (symbol? exp) (trie-from-map {"name" (new-trie (str exp))} "variable")
        ;; Literal
        true (do (assert (or (number? exp)
                             (string? exp)
                             (boolean? exp))
                         ["bogus expression" exp])
                 (trie-from-map {"value" (new-trie exp)} "literal"))))
        

(defn from-clojure [exp]
  (let [answer (from-clojure-1 exp)]
    (assert (trie? answer) ["bad answer" answer])
    answer))