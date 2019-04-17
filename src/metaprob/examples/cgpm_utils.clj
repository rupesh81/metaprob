(ns metaprob.examples.cgpm_utils
  (:require
    [metaprob.prelude :refer :all]
    [metaprob.inference :refer :all]))

; ------------------------
; CGPM INTERFACE UTILITIES
; ------------------------

; Compute simple average of items.
(defn compute-avg
  [items]
    (/ (reduce + 0 items) (count items)))

; Check if collection contains item before invoking get.
; Throws an assertion error in item is not in collection (instead of nil).
(defn safe-get
  [collection item]
    (if (contains? collection item)
      (get collection item)
      (assert false (format "no such key %s in key set %s"
                            item (keys collection)))))

; Assert that value is valid for given statistical type.
(defn validate-cell
  [stattype value]
    (assert ((get stattype :valid?) value)
            (format "invalid value \"%s\" for stattype %s"
                    value (get stattype :name))))

; Assert that row of values are valid for given statistical types.
; addrs-types is a dictionary from keys to statistical types.
; addrs-vals is a dictionary from keys to values.
; check-all-exist? boolean to assert keys(addrs-vals) == keys(addrs-types).
(defn validate-row
  [addrs-types addrs-vals check-all-exist?]
    (let [violations (filter
                        (fn [[k v]] (validate-cell (safe-get addrs-types k) v))
                        addrs-vals)]
    (assert (= (count violations) 0)
               (format "invalid values %s for types %s"
                        violations addrs-types))
    (if check-all-exist?
        (assert (= (set (keys addrs-types)) (set (keys addrs-vals)))
                (format "row %s must have values for %s"
                        addrs-vals addrs-types))
        nil)))

; Assert that set-a and set-b have the same number of items.
(defn assert-same-length
  [set-a set-b name-a name-b]
    (assert (= (count set-a) (count set-b))
            (format "%s %s and %s %s must have same length"
                    name-a set-a name-b set-b)))

; Assert that set-a and set-b have no overlapping items.
(defn assert-no-overlap
  [set-a set-b name-a name-b]
    (let [overlap (clojure.set/intersection set-a set-b)]
    (assert (= (count overlap) 0)
            (format "%s %s and %s %s must be disjoint"
                    name-a set-a name-b set-b))))

; Assert that items is a subset of the keys of the given collection.
(defn assert-has-keys
  [collection items]
    (let [collection-keys (set (keys collection))
          invalid-items (clojure.set/difference items collection-keys)]
    (assert (= (count invalid-items) 0)
            (format "key set %s does not have some of the keys in %s"
                     collection-keys items))))

; Assert that the input-address-map for a CGPM is valid.
; The values of input-address-map must be contiguous integers starting at 0.
(defn assert-valid-input-address-map
  [input-address-map]
    (let [invalid-address-values (filter
                                   (fn [[k v]] (not (int? v)))
                                   input-address-map)
          ; Make sure that the values are all integers.
          _ (assert (= (count invalid-address-values) 0)
                       (format "input addresses must map to integers %s" input-address-map))
          ; Make sure that the integers are consecutive 0...n-1
          sorted-address-values (sort (vals input-address-map))
          num-inputs (count sorted-address-values)]
    (assert (= (range 0 num-inputs) sorted-address-values)
            (format "input addresses must map to consecutive integers %s"
                    input-address-map))))

; Assert that the output-address-map for a GPM is valid.
; The values of output-address-map must be distinct.
(defn assert-valid-output-address-map
  [output-address-map]
    ; The values of the address map should be distinct.
    (let [values (vals output-address-map)]
      (assert (= (count (set values)) (count values))
        (format "addresses should have distinct values %s"
          output-address-map))))

; Convert a CGPM row into a Metaprob target-trace.
(defn rekey-addrs-vals
  [address-map addrs-vals]
    (let [converter (fn [[k v]] [(safe-get address-map k) {:value v}])]
      (into {} (map converter addrs-vals))))

; Convert a list of CGPM addresses into Metaprob addresses.
(defn rekey-addrs
  [address-map addrs]
    (let [convert (fn [k] (safe-get address-map k))]
      (map convert addrs)))

; Convert a CGPM row into list of arguments of Metaprob gen.
(defn extract-input-list
  [address-map addrs-vals]
    (let [compr (fn [k1 k2] (< (get address-map k1) (get address-map k2)))
          ordered-keys (sort compr (keys addrs-vals))]
      (map (fn [k] (get addrs-vals k)) ordered-keys)))

; Convert a Metaprob trace to a CGPM row.
(defn extract-samples-from-trace
  [trace target-addrs output-addr-map]
    (let [extract (fn [k]
                    (let [result (get trace (safe-get output-addr-map k))]
                      [k (safe-get result :value)]))]
     (into {} (map extract target-addrs))))

; Rewrite keys of dictionary according to keymap.
(defn rekey-dict
  [keymap dict]
    (into {} (map (fn [[k v]] [(safe-get keymap k) v]) dict)))
