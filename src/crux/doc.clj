(ns crux.doc
  (:require [clojure.tools.logging :as log]
            [crux.byte-utils :as bu]
            [crux.index :as idx]
            [crux.kv-store :as ks]
            [crux.db :as db]
            [crux.lru :as lru]
            [taoensso.nippy :as nippy])
  (:import [java.nio ByteBuffer]
           [java.io Closeable]
           [java.util Date]))

(set! *unchecked-math* :warn-on-boxed)

;; Docs

(defn- attribute-value->value+content-hashes [attr k max-v]
  (let [max-seek-k (idx/encode-attribute+value-prefix-key attr (or max-v idx/empty-byte-array))]
    (when (and k (not (neg? (bu/compare-bytes max-seek-k k (alength max-seek-k)))))
      [(idx/decode-attribute+value+content-hash-key->value+content-hash k)])))

(defrecord DocAttrbuteValueIndex [i attr max-v]
  db/Index
  (-seek-values [this k]
    (when-let [k (->> (or k idx/empty-byte-array)
                      (idx/encode-attribute+value-prefix-key attr)
                      (ks/-seek i))]
      (attribute-value->value+content-hashes attr k max-v)))
  (-next-values [this]
    (when-let [k (ks/-next i)]
      (attribute-value->value+content-hashes attr k max-v))))

(defn- normalize-value [v]
  (cond-> v
    (not (or (vector? v)
             (set? v))) (vector)))

(defn index-doc [kv content-hash doc]
  (ks/store kv (for [[k v] doc
                     v (normalize-value v)]
                 [(idx/encode-attribute+value+content-hash-key k v content-hash)
                  idx/empty-byte-array])))

(defn delete-doc-from-index [kv content-hash doc]
  (ks/delete kv (for [[k v] doc
                      v (normalize-value v)]
                  (idx/encode-attribute+value+content-hash-key k v content-hash))))

(defrecord DocObjectStore [kv]
  db/ObjectStore
  (get-objects [this ks]
    (with-open [snapshot (ks/new-snapshot kv)
                i (ks/new-iterator snapshot)]
      (->> (for [seek-k (->> (map idx/encode-doc-key ks)
                             (sort bu/bytes-comparator))
                 :let [k (ks/-seek i seek-k)]
                 :when (and k (bu/bytes=? seek-k k))]
             [(idx/decode-doc-key k)
              (nippy/fast-thaw (ks/-value i))])
           (into {}))))
  (put-objects [this kvs]
    (ks/store kv (for [[k v] kvs]
                   [(idx/encode-doc-key k)
                    (nippy/fast-freeze v)])))
  (delete-objects [this ks]
    (ks/delete kv (map idx/encode-doc-key ks)))

  Closeable
  (close [_]))

;; Meta

(defn store-meta [kv k v]
  (ks/store kv [[(idx/encode-meta-key k)
                 (nippy/fast-freeze v)]]))

(defn read-meta [kv k]
  (with-open [snapshot (ks/new-snapshot kv)
              i (ks/new-iterator snapshot)]
    (when-let [k (ks/-seek i (idx/encode-meta-key k))]
      (nippy/fast-thaw (ks/-value i)))))

;; Utils

(defn- all-keys-in-prefix
  ([i prefix]
   (all-keys-in-prefix i prefix false))
  ([i prefix entries?]
   ((fn step [f-cons f-next]
          (lazy-seq
           (let [k (f-cons)]
             (when (and k (bu/bytes=? prefix k))
               (cons (if entries?
                       [k (ks/-value i)]
                       k) (step f-next f-next))))))
    #(ks/-seek i prefix) #(ks/-next i))))

;; Entities

(defn- enrich-entity-map [entity-map content-hash]
  (assoc entity-map :content-hash (some-> content-hash not-empty idx/new-id)))

(def ^:private ^:const entity-prefix-size (+ Short/BYTES idx/id-size))

(defrecord EntityAsOfIndex [i business-time transact-time]
  db/Index
  (db/-seek-values [this k]
    (let [seek-k (idx/encode-entity+bt+tt-prefix-key
                  k
                  business-time
                  transact-time)]
      (loop [k (ks/-seek i seek-k)]
        (when (and k (bu/bytes=? seek-k entity-prefix-size k))
          (let [v (ks/-value i)
                entity-map (-> (idx/decode-entity+bt+tt+tx-id-key k)
                               (enrich-entity-map v))]
            (if (<= (compare (:tt entity-map) transact-time) 0)
              (when-not (bu/bytes=? idx/nil-id-bytes v)
                [entity-map])
              (recur (ks/-next i))))))))

  (db/-next-values [this]))

(defn entities-at [snapshot entities business-time transact-time]
  (with-open [i (ks/new-iterator snapshot)]
    (let [entity-as-of-idx (->EntityAsOfIndex i business-time transact-time)]
      (vec (mapcat #(db/-seek-values entity-as-of-idx %) entities)))))

(defrecord ContentHashEntityIndex [i]
  db/Index
  (db/-seek-values [this k]
    (->> (idx/encode-content-hash-prefix-key k)
         (all-keys-in-prefix i)
         (map idx/decode-content-hash+entity-key->entity)))

  (db/-next-values [this]))

(defn eids-for-content-hash [snapshot content-hash]
  (with-open [i (ks/new-iterator snapshot)]
    (let [content-hash-idx (->ContentHashEntityIndex i)]
      (vec (db/-seek-values content-hash-idx content-hash)))))

(defn- entities-for-content-hashes [content-hash-idx entity-as-of-idx content-hashes]
  (seq (for [content-hash content-hashes
             entity (db/-seek-values content-hash-idx content-hash)
             entity-map (db/-seek-values entity-as-of-idx entity)
             :when (= content-hash (:content-hash entity-map))]
         entity-map)))

(defrecord EntityAttributeValueVirtualIndex [doc-idx content-hash-entity-idx entity-as-of-idx]
  db/Index
  (-seek-values [this k]
    (when-let [[[v content-hash]] (db/-seek-values doc-idx k)]
      [[v (entities-for-content-hashes content-hash-entity-idx entity-as-of-idx [content-hash])]]))
  (-next-values [this]
    (when-let [[[v content-hash]] (db/-next-values doc-idx)]
      [[v (entities-for-content-hashes content-hash-entity-idx entity-as-of-idx [content-hash])]])))

(defn entities-by-attribute-value-at [snapshot attr min-v max-v business-time transact-time]
  (with-open [di (ks/new-iterator snapshot)
              ci (ks/new-iterator snapshot)
              ei (ks/new-iterator snapshot)]
    (let [doc-idx (->DocAttrbuteValueIndex di attr max-v)
          content-hash-entity-idx (->ContentHashEntityIndex ci)
          entity-as-of-idx (->EntityAsOfIndex ei business-time transact-time)
          entity-attribute-idx (->EntityAttributeValueVirtualIndex doc-idx content-hash-entity-idx entity-as-of-idx)]
      (when-let [k (db/-seek-values entity-attribute-idx min-v)]
        (->> (repeatedly #(db/-next-values entity-attribute-idx))
             (take-while identity)
             (apply concat k)
             (mapcat second)
             (vec))))))

(defn all-entities [snapshot business-time transact-time]
  (with-open [i (ks/new-iterator snapshot)]
    (let [eids (->> (all-keys-in-prefix i (idx/encode-entity+bt+tt-prefix-key))
                    (map (comp :eid idx/decode-entity+bt+tt+tx-id-key))
                    (distinct))]
      (entities-at snapshot eids business-time transact-time))))

(defn entity-history [snapshot entity]
  (with-open [i (ks/new-iterator snapshot)]
    (let [seek-k (idx/encode-entity+bt+tt-prefix-key entity)]
      (vec (for [[k v] (all-keys-in-prefix i seek-k true)]
             (-> (idx/decode-entity+bt+tt+tx-id-key k)
                 (enrich-entity-map v)))))))

;; Join

;; TODO: First cut, needs loads of work!
;;    1. avoid resorting, using the mod trick.
;;    2. return all the data per attribute, needs doing next on match
;;    for all iterators until they all changed.
;;    3. cleanup/refactoring.
(defn unary-leapfrog-join [snapshot attrs min-v max-v business-time transact-time]
  (let [attr->di (zipmap attrs (repeatedly #(ks/new-iterator snapshot)))]
    (try
      (with-open [ci (ks/new-iterator snapshot)
                  ei (ks/new-iterator snapshot)]
        (let [content-hash-entity-idx (->ContentHashEntityIndex ci)
              entity-as-of-idx (->EntityAsOfIndex ei business-time transact-time)
              update-values (fn [attr v+entities]
                              (->> (for [[v entities] v+entities
                                         {:keys [eid content-hash]} entities]
                                     (do (log/debug :at attr (bu/bytes->hex v))
                                         (sorted-map-by bu/bytes-comparator v [eid])))
                                   (apply merge-with concat)))
              attr->key+idx (->> (for [attr attrs
                                       :let [doc-idx (->DocAttrbuteValueIndex (get attr->di attr) attr max-v)
                                             entity-idx (->EntityAttributeValueVirtualIndex doc-idx content-hash-entity-idx entity-as-of-idx)
                                             start-v+entities (db/-seek-values entity-idx min-v)]]
                                   [attr [(update-values attr start-v+entities) entity-idx]])
                                 (into {}))]
          (loop [attr->key+idx attr->key+idx
                 acc (sorted-map-by bu/bytes-comparator)]
            (let [sorted (sort-by (comp first keys first val) bu/bytes-comparator attr->key+idx)
                  attr (first (keys sorted))
                  next-k (first (keys (first (val (last sorted)))))
                  match? (reduce
                          #(when (and %1 (bu/bytes=? %1 %2))
                             %2)
                          (map (comp first keys first val) attr->key+idx))
                  acc (if match?
                        (do (log/debug :match attrs (bu/bytes->hex next-k))
                            (assoc acc next-k (reduce into #{} (mapcat (comp vals first val) attr->key+idx))))
                        acc)
                  idx (second (get attr->key+idx attr))
                  next-v+entities (if match?
                                    (do (log/debug :next attr)
                                        (db/-next-values idx))
                                    (do (log/debug :seek attr (bu/bytes->hex next-k))
                                        (db/-seek-values idx (reify idx/ValueToBytes
                                                               (value->bytes [_]
                                                                 next-k)))))]
              (if (seq next-v+entities)
                (let [new-attr->key+idx (assoc attr->key+idx attr [(update-values attr next-v+entities) idx])]
                  (if (= (mapcat (comp vals first val) new-attr->key+idx)
                         (mapcat (comp vals first val) attr->key+idx))
                    acc
                    (recur new-attr->key+idx acc)))
                acc)))))
      (finally
        (doseq [i (vals attr->di)]
          (.close ^Closeable i))))))

;; Caching

(defrecord CachedObjectStore [cache object-store]
  db/ObjectStore
  (get-objects [this ks]
    (->> (for [k ks]
           [k (lru/compute-if-absent
               cache
               k
               #(get (db/get-objects object-store [%]) %))])
         (into {})))
  (put-objects [this kvs]
    (db/put-objects object-store kvs))
  (delete-objects [this ks]
    (doseq [k ks]
      (lru/evict cache k))
    (db/delete-objects object-store ks))

  Closeable
  (close [_]))

;; Query

(defrecord DocEntity [object-store eid content-hash bt]
  db/Entity
  (attr-val [this ident]
    (get (db/->map this) ident))
  (->id [this]
    ;; TODO: we want to get rid of the need for :crux.db/id
    (or (db/attr-val this :crux.db/id) eid))
  (->map [this]
    (get (db/get-objects object-store [content-hash]) content-hash))
  (->business-time [this]
    bt)
  (eq? [this that]
    (= eid (:eid that))))

(defrecord DocCachedIterator [iterators i]
  ks/KvIterator
  (-seek [_ k]
    (ks/-seek i k))
  (-next [_]
    (ks/-next i))
  (-value [_]
    (ks/-value i))

  Closeable
  (close [_]
    (swap! iterators conj i)))

(defrecord DocSnapshot [^Closeable snapshot iterators]
  ks/KvSnapshot
  (new-iterator [_]
    (let [is @iterators]
      (if-let [i (first is)]
        (if (compare-and-set! iterators is (disj is i))
          (->DocCachedIterator iterators i)
          (recur))
        (->> (ks/new-iterator snapshot)
             (->DocCachedIterator iterators)))))

  Closeable
  (close [_]
    (doseq [^Closeable i @iterators]
      (.close i))
    (.close snapshot)))

(defrecord DocDatasource [kv object-store business-time transact-time]
  db/Datasource
  (new-query-context [this]
    (->DocSnapshot (ks/new-snapshot kv) (atom #{})))

  (entities [this query-context]
    (for [entity-map (all-entities query-context business-time transact-time)]
      (map->DocEntity (assoc entity-map :object-store object-store))))

  (entities-for-attribute-value [this query-context ident min-v max-v]
    (for [entity-map (entities-by-attribute-value-at query-context ident min-v max-v business-time transact-time)]
      (map->DocEntity (assoc entity-map :object-store object-store))))

  (entity-history [this query-context eid]
    (for [entity-map (entity-history query-context eid)]
      (map->DocEntity (assoc entity-map :object-store object-store))))

  (entity [this query-context eid]
    (when-let [entity-map (first (entities-at query-context [eid] business-time transact-time)) ]
      (map->DocEntity (assoc entity-map :object-store object-store)))))

(def ^:const default-await-tx-timeout 10000)

(defn- await-tx-time [kv transact-time ^long timeout]
  (let [timeout-at (+ timeout (System/currentTimeMillis))]
    (while (pos? (compare transact-time (read-meta kv :crux.tx-log/tx-time)))
      (Thread/sleep 100)
      (when (>= (System/currentTimeMillis) timeout-at)
        (throw (IllegalStateException. (str "Timed out waiting for: " transact-time)))))))

(def ^:const default-doc-cache-size 10240)

(defn- named-cache [state cache-name cache-size]
  (get (swap! state
              update
              cache-name
              (fn [cache]
                (or cache (lru/new-cache cache-size))))
       cache-name))

(defn- new-cached-object-store [kv cache-size]
  (->CachedObjectStore (named-cache (:state kv)::doc-cache cache-size)
                       (->DocObjectStore kv)))

(defn db
  ([kv]
   (db kv (Date.)))
  ([kv business-time]
   (->DocDatasource kv
                    (new-cached-object-store kv default-doc-cache-size)
                    business-time
                    (Date.)))
  ([kv business-time transact-time]
   (await-tx-time kv transact-time default-await-tx-timeout)
   (->DocDatasource kv
                    (new-cached-object-store kv default-doc-cache-size)
                    business-time
                    transact-time)))
