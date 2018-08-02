(ns crux.query
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.walk :as w]
            [com.stuartsierra.dependency :as dep]
            [crux.byte-utils :as bu]
            [crux.doc :as doc]
            [crux.index :as idx]
            [crux.io :as cio]
            [crux.lru :as lru]
            [crux.kv-store :as ks]
            [crux.db :as db])
  (:import [java.util Comparator]))

(defn- logic-var? [x]
  (symbol? x))

(def ^:private literal? (complement logic-var?))
(def ^:private db-ident? keyword?)

(defn- expression-spec [sym spec]
  (s/and seq?
         #(= sym (first %))
         (s/conformer next)
         spec))

(def ^:private built-ins '#{and == !=})

(s/def ::triple (s/and vector? (s/cat :e (some-fn logic-var? db-ident?)
                                      :a db-ident?
                                      :v (s/? any?))))

(s/def ::pred-fn (s/and symbol?
                        (complement built-ins)
                        (s/conformer #(or (some-> % resolve var-get) %))
                        (some-fn fn? logic-var?)))
(s/def ::pred (s/and vector? (s/cat :pred (s/and list?
                                                 (s/cat :pred-fn ::pred-fn
                                                        :args (s/* any?)))
                                    :return (s/? logic-var?))))

(s/def ::rule (s/and list? (s/cat :name (s/and symbol? (complement built-ins))
                                  :args (s/+ any?))))

(s/def ::range-op '#{< <= >= >})
(s/def ::range (s/tuple (s/and list?
                               (s/or :sym-val (s/cat :op ::range-op
                                                     :sym logic-var?
                                                     :val literal?)
                                     :val-sym (s/cat :op ::range-op
                                                     :val literal?
                                                     :sym logic-var?)))))

(s/def ::unify (s/tuple (s/and list?
                               (s/cat :op '#{== !=}
                                      :args (s/+ any?)))))

(s/def ::args-list (s/coll-of logic-var? :kind vector? :min-count 1))

(s/def ::not (expression-spec 'not (s/+ ::term)))
(s/def ::not-join (expression-spec 'not-join (s/cat :args ::args-list
                                                    :body (s/+ ::term))))

(s/def ::and (expression-spec 'and (s/+ ::term)))
(s/def ::or-body (s/+ (s/or :term ::term
                            :and ::and)))
(s/def ::or (expression-spec 'or ::or-body))
(s/def ::or-join (expression-spec 'or-join (s/cat :args ::args-list
                                                  :body ::or-body)))

(s/def ::term (s/or :triple ::triple
                    :not ::not
                    :not-join ::not-join
                    :or ::or
                    :or-join ::or-join
                    :range ::range
                    :unify ::unify
                    :rule ::rule
                    :pred ::pred))

(s/def ::find ::args-list)
(s/def ::where (s/coll-of ::term :kind vector? :min-count 1))

(s/def ::arg-tuple (s/map-of (some-fn logic-var? keyword?) any?))
(s/def ::args (s/coll-of ::arg-tuple :kind vector?))

(s/def ::rule-head (s/and list?
                          (s/cat :name (s/and symbol? (complement built-ins))
                                 :bound-args (s/? ::args-list)
                                 :args (s/* logic-var?))))
(s/def ::rule-definition (s/and vector?
                                (s/cat :head ::rule-head
                                       :body (s/+ ::term))))
(s/def ::rules (s/coll-of ::rule-definition :kind vector? :min-count 1))
(s/def ::offset pos-int?)
(s/def ::limit pos-int?)

(s/def ::order-element (s/and vector?
                              (s/cat :var logic-var? :direction (s/? #{:asc :desc}))))
(s/def ::order-by (s/coll-of ::order-element :kind vector?))

(s/def ::query (s/keys :req-un [::find ::where] :opt-un [::args ::rules ::offset ::limit ::order-by]))

;; NOTE: :min-count generates boxed math warnings, so this goes below
;; the spec.
(set! *unchecked-math* :warn-on-boxed)

(defn- blank-var? [v]
  (when (logic-var? v)
    (re-find #"^_\d*$" (name v))))

(defn- normalize-triple-clause [{:keys [e a v] :as clause}]
  (cond-> clause
    (or (blank-var? v)
        (nil? v))
    (assoc :v (gensym "_"))
    (blank-var? e)
    (assoc :e (gensym "_"))
    (nil? a)
    (assoc :a :crux.db/id)))

(def ^:private pred->built-in-range-pred {< (comp neg? compare)
                                          <= (comp not pos? compare)
                                          > (comp pos? compare)
                                          >= (comp not neg? compare)})

(def ^:private range->inverse-range '{< >=
                                      <= >
                                      > <=
                                      >= <})

;; NOTE: This could be optimised if the binary join had a mechanism to
;; propagate the value the first occurrence to the second. This would
;; avoid having to scan the entire second var to find the value we
;; already know we're looking for. This could be implemented as a
;; relation that gets updated once we know the first value. In the
;; more generic case, unification constraints could propagate
;; knowledge of the first bound value from one join to another.
(defn- rewrite-self-join-triple-clause [{:keys [e v] :as triple}]
  (let [v-var (gensym v)]
    {:triple [(assoc triple :v v-var)]
     :unify [{:op '== :args [v-var e]}]}))

(defn- normalize-clauses [clauses]
  (->> (for [[type clause] clauses]
         (if (= :triple type)
           (let [{:keys [e v] :as clause} (normalize-triple-clause clause)]
             (if (and (logic-var? e) (= e v))
               (rewrite-self-join-triple-clause clause)
               {:triple [clause]}))
           {type [(case type
                    :pred (let [{:keys [pred]} clause
                                {:keys [pred-fn args]} pred]
                            (if-let [range-pred (and (= 2 (count args))
                                                     (every? logic-var? args)
                                                     (get pred->built-in-range-pred pred-fn))]
                              (assoc-in clause [:pred :pred-fn] range-pred)
                              clause))
                    :range (let [[type clause] (first clause)]
                             (if (= :val-sym type)
                               (update clause :op range->inverse-range)
                               clause))
                    :unify (first clause)
                    clause)]}))
       (apply merge-with into)))

(defn- collect-vars [{triple-clauses :triple
                      unify-clauses :unify
                      not-clauses :not
                      not-join-clauses :not-join
                      or-clauses :or
                      or-join-clauses :or-join
                      pred-clauses :pred
                      range-clauses :range
                      rule-clauses :rule}]
  (let [or-vars (->> (for [or-clause or-clauses
                           [type sub-clauses] or-clause]
                       (collect-vars (normalize-clauses (case type
                                                          :term [sub-clauses]
                                                          :and sub-clauses))))
                     (apply merge-with set/union))
        not-join-vars (set (for [not-join-clause not-join-clauses
                                 arg (:args not-join-clause)]
                             arg))
        not-vars (->> (for [not-clause not-clauses]
                        (collect-vars (normalize-clauses not-clause)))
                      (apply merge-with set/union))
        or-join-vars (set (for [or-join-clause or-join-clauses
                                arg (:args or-join-clause)]
                            arg))]
    {:e-vars (set (for [{:keys [e]} triple-clauses
                        :when (logic-var? e)]
                    e))
     :v-vars (set (for [{:keys [v]} triple-clauses
                        :when (logic-var? v)]
                    v))
     :unification-vars (set (for [{:keys [args]} unify-clauses
                                  arg args
                                  :when (logic-var? arg)]
                              arg))
     :not-vars (->> (vals not-vars)
                    (reduce into not-join-vars))
     :pred-vars (set (for [{:keys [pred return]} pred-clauses
                           arg (cons return (cons (:pred-fn pred) (:args pred)))
                           :when (logic-var? arg)]
                       arg))
     :pred-return-vars (set (for [{:keys [pred return]} pred-clauses
                                  :when (logic-var? return)]
                              return))
     :range-vars (set (for [{:keys [sym]} range-clauses]
                        sym))
     :or-vars (apply set/union (vals or-vars))
     :rule-vars (set/union (set (for [{:keys [args]} rule-clauses
                                      arg args
                                      :when (logic-var? arg)]
                                  arg))
                           or-join-vars)}))

(defn- build-v-var-range-constraints [e-vars range-clauses]
  (let [v-var->range-clauses (->> (for [{:keys [sym] :as clause} range-clauses]
                                    (if (contains? e-vars sym)
                                      (throw (IllegalArgumentException.
                                              (str "Cannot add range constraints on entity variable: "
                                                   (pr-str clause))))
                                      clause))
                                  (group-by :sym))]
    (->> (for [[v-var clauses] v-var->range-clauses]
           [v-var (->> (for [{:keys [op val]} clauses
                             :let [type-prefix (idx/value-bytes-type-id (idx/value->bytes val))]]
                         (case op
                           < #(-> (doc/new-less-than-virtual-index % val)
                                  (doc/new-prefix-equal-virtual-index type-prefix))
                           <= #(-> (doc/new-less-than-equal-virtual-index % val)
                                   (doc/new-prefix-equal-virtual-index type-prefix))
                           > #(-> (doc/new-greater-than-virtual-index % val)
                                  (doc/new-prefix-equal-virtual-index type-prefix))
                           >= #(-> (doc/new-greater-than-equal-virtual-index % val)
                                   (doc/new-prefix-equal-virtual-index type-prefix))))
                       (apply comp))])
         (into {}))))

(defn- arg-for-var [arg var]
  (or (get arg (symbol (name var)))
      (get arg (keyword (name var)))))

(defn- update-binary-index! [snapshot {:keys [business-time transact-time]} binary-idx vars-in-join-order v-var->range-constriants]
  (let [{:keys [clause names]} (meta binary-idx)
        {:keys [e a v]} clause
        order (filter (set (vals names)) vars-in-join-order)
        v-range-constraints (get v-var->range-constriants v)
        entity-as-of-idx (doc/new-entity-as-of-index snapshot business-time transact-time)]
    (if (= (:v names) (first order))
      (let [v-doc-idx (doc/new-doc-attribute-value-entity-value-index snapshot a)
            e-idx (doc/new-doc-attribute-value-entity-entity-index snapshot a v-doc-idx entity-as-of-idx)]
        (log/debug :join-order :ave (pr-str v) e (pr-str clause))
        (doc/update-binary-join-order! binary-idx (doc/wrap-with-range-constraints v-doc-idx v-range-constraints) e-idx))
      (let [e-doc-idx (doc/new-doc-attribute-entity-value-entity-index snapshot a entity-as-of-idx)
            v-idx (-> (doc/new-doc-attribute-entity-value-value-index snapshot a e-doc-idx)
                      (doc/wrap-with-range-constraints v-range-constraints))]
        (log/debug :join-order :aev e (pr-str v) (pr-str clause))
        (doc/update-binary-join-order! binary-idx e-doc-idx v-idx)))))

(defn- triple-joins [triple-clauses var->joins non-leaf-vars arg-vars]
  (let [v->clauses (group-by :v triple-clauses)]
    (->> triple-clauses
         (reduce
          (fn [[deps var->joins] {:keys [e a v] :as clause}]
            (let [e-var e
                  v-var (if (logic-var? v)
                          v
                          (gensym (str "literal_" v "_")))
                  join {:id (gensym "triple")
                        :name e-var
                        :idx-fn #(-> (doc/new-binary-join-virtual-index)
                                     (with-meta {:clause clause
                                                 :names {:e e-var
                                                         :v v-var}}))}
                  var->joins (merge-with into var->joins {v-var [join]
                                                          e-var [join]})
                  var->joins (if (literal? e)
                               (merge-with into var->joins {e-var [{:idx-fn #(doc/new-relation-virtual-index e-var [[e]] 1)}]})
                               var->joins)
                  var->joins (if (literal? v)
                               (merge-with into var->joins {v-var [{:idx-fn #(doc/new-relation-virtual-index v-var [[v]] 1)}]})
                               var->joins)
                  v-is-leaf? (and (logic-var? v)
                                  (= 1 (count (get v->clauses v)))
                                  (not (contains? non-leaf-vars v)))]
              [(cond
                 (and (logic-var? v)
                      (or (literal? e)
                          (contains? arg-vars e)))
                 (conj deps [[e-var] [v-var]])
                 (and (or (literal? v)
                          (contains? arg-vars v))
                      (logic-var? e))
                 (conj deps [[v-var] [e-var]])
                 v-is-leaf?
                 (conj deps [[e-var] [v-var]])
                 ;; NOTE: This is to default join order to ave as it
                 ;; used to be. The vars have to depend on each other
                 ;; as they're a pair, calculate-join-order needs this
                 ;; for or and predicates, so we pick this order.
                 :else
                 (conj deps [[v-var] [e-var]]))
               var->joins]))
          [[] var->joins]))))

(defn- validate-args [args]
  (let [ks (keys (first args))]
    (doseq [m args]
      (when-not (every? #(contains? m %) ks)
        (throw (IllegalArgumentException.
                (str "Argument maps need to contain the same keys as first map: " ks " " (keys m))))))))

(defn- arg-vars [args]
  (let [ks (keys (first args))]
    (set (for [k ks]
           (symbol (name k))))))

(defn- arg-joins [arg-vars e-vars v-var->range-constriants var->joins]
  (let [idx-id (gensym "args")
        join {:id idx-id
              :idx-fn #(doc/new-relation-virtual-index idx-id
                                                       []
                                                       (count arg-vars))}]
    [idx-id
     (->> arg-vars
          (reduce
           (fn [var->joins arg-var]
             (->> {arg-var
                   [(assoc join :name (symbol "crux.query.value" (name arg-var)))]}
                  (merge-with into var->joins)))
           var->joins))]))

(defn- pred-joins [pred-clauses v-var->range-constriants var->joins]
  (->> pred-clauses
       (reduce
        (fn [[pred-clause+idx-ids var->joins] {:keys [return] :as pred-clause}]
          (if return
            (let [idx-id (gensym "pred-return")
                  join {:id idx-id
                        :idx-fn #(doc/new-relation-virtual-index idx-id
                                                                 []
                                                                 1
                                                                 [(get v-var->range-constriants return)])
                        :name (symbol "crux.query.value" (name return))}]
              [(conj pred-clause+idx-ids [pred-clause idx-id])
               (merge-with into var->joins {return [join]})])
            [(conj pred-clause+idx-ids [pred-clause])
             var->joins]))
        [[] var->joins])))

(defn- or-joins [rules or-type or-clauses var->joins known-vars]
  (->> or-clauses
       (reduce
        (fn [[or-clause+idx-id+or-branches known-vars var->joins] clause]
          (let [or-join? (= :or-join or-type)
                or-branches (for [[type sub-clauses] (case or-type
                                                       :or clause
                                                       :or-join (:body clause))
                                  :let [where (case type
                                                :term [sub-clauses]
                                                :and sub-clauses)
                                        body-vars (->> (collect-vars (normalize-clauses where))
                                                       (vals)
                                                       (reduce into #{}))
                                        or-vars (if or-join?
                                                  (set (:args clause))
                                                  body-vars)
                                        free-vars (set/difference or-vars known-vars)]]
                              (do (when or-join?
                                    (doseq [var or-vars
                                            :when (not (contains? body-vars var))]
                                      (throw (IllegalArgumentException.
                                              (str "Or join variable never used: " var " " (pr-str clause))))))
                                  {:or-vars or-vars
                                   :free-vars free-vars
                                   :bound-vars (set/difference or-vars free-vars)
                                   :where where}))
                free-vars (:free-vars (first or-branches))
                idx-id (gensym "or-free-vars")
                join (when (seq free-vars)
                       {:id idx-id
                        :idx-fn #(doc/new-relation-virtual-index idx-id
                                                                 []
                                                                 (count free-vars))})]
            (when (not (apply = (map :or-vars or-branches)))
              (throw (IllegalArgumentException.
                      (str "Or requires same logic variables: " (pr-str clause)))))
            [(conj or-clause+idx-id+or-branches [clause idx-id or-branches])
             (into known-vars free-vars)
             (apply merge-with into var->joins (for [v free-vars]
                                                 {v [(assoc join :name (symbol "crux.query.value" (name v)))]}))]))
        [[] known-vars var->joins])))

(defn- build-var-bindings [var->attr v-var->e e->v-var var->values-result-index join-depth vars]
  (->> (for [var vars
             :let [e (get v-var->e var var)
                   join-depth (or (max (long (get var->values-result-index e -1))
                                       (long (get var->values-result-index (get e->v-var e) -1)))
                                  (dec (long join-depth)))
                   result-index (get var->values-result-index var)]]
         [var {:e-var e
               :var var
               :attr (get var->attr var)
               :result-index result-index
               :join-depth join-depth
               :result-name e
               :type :entity}])
       (into {})))

(defn- value-var-binding [var result-index type]
  {:var var
   :result-name (symbol "crux.query.value" (name var))
   :result-index result-index
   :join-depth result-index
   :type type})

(defn- build-arg-var-bindings [var->values-result-index arg-vars]
  (->> (for [var arg-vars
             :let [result-index (get var->values-result-index var)]]
         [var (value-var-binding var result-index :arg)])
       (into {})))

(defn- build-pred-return-var-bindings [var->values-result-index pred-clauses]
  (->> (for [{:keys [return]} pred-clauses
             :when return
             :let [result-index (get var->values-result-index return)]]
         [return (value-var-binding return result-index :pred)])
       (into {})))

(defn- build-or-free-var-bindings [var->values-result-index or-clause+relation+or-branches]
  (->> (for [[_ _ or-branches] or-clause+relation+or-branches
             var (:free-vars (first or-branches))
             :let [result-index (get var->values-result-index var)]]
         [var (value-var-binding var result-index :or)])
       (into {})))

(defn- bound-result->join-result [{:keys [result-name value? entity value] :as result}]
  (if value?
    {result-name value}
    {result-name entity}))

(defn- bound-results-for-var [snapshot object-store var->bindings join-keys join-results var]
  (let [{:keys [e-var var attr result-index result-name type]} (get var->bindings var)]
    (if (= "crux.query.value" (namespace result-name))
      (let [value (get join-results result-name)]
        {:value value
         :result-name result-name
         :type type
         :var var
         :value? true})
      (when-let [{:keys [content-hash] :as entity} (get join-results e-var)]
        (let [value-bytes (get join-keys result-index)
              doc (get (db/get-objects object-store snapshot [content-hash]) content-hash)
              values (doc/normalize-value (get doc attr))
              value (first (if (or (nil? value-bytes)
                                   (= (count values) 1))
                             values
                             (for [value values
                                   :when (bu/bytes=? value-bytes (idx/value->bytes value))]
                               value)))]
          {:value value
           :e-var e-var
           :v-var var
           :attr attr
           :doc doc
           :entity entity
           :result-name result-name
           :type type
           :var var
           :value? false})))))

(declare build-sub-query)

(defn- calculate-constraint-join-depth [var->bindings vars]
  (->> (for [var vars]
         (get-in var->bindings [var :join-depth] -1))
       (apply max -1)
       (long)
       (inc)))

(defn- validate-existing-vars [var->bindings clause vars]
  (doseq [var vars
          :when (not (contains? var->bindings var))]
    (throw (IllegalArgumentException.
            (str "Clause refers to unknown variable: "
                 var " " (pr-str clause))))))

(defn- build-pred-constraints [pred-clause+idx-ids var->bindings]
  (for [[{:keys [pred return] :as clause} idx-id] pred-clause+idx-ids
        :let [{:keys [pred-fn args]} pred
              pred-vars (filter logic-var? (cons pred-fn args))
              pred-join-depth (calculate-constraint-join-depth var->bindings pred-vars)]]
    (do (validate-existing-vars var->bindings clause pred-vars)
        {:join-depth pred-join-depth
         :constraint-fn
         (fn pred-constraint [snapshot {:keys [object-store] :as db} idx-id->idx join-keys join-results]
           (let [[pred-fn & args] (for [arg (cons pred-fn args)]
                                    (if (logic-var? arg)
                                      (:value (bound-results-for-var snapshot object-store var->bindings join-keys join-results arg))
                                      arg))]
             (when-let [pred-result (apply pred-fn args)]
               (when return
                 (doc/update-relation-virtual-index! (get idx-id->idx idx-id) [[pred-result]]))
               join-results)))})))

(defn- single-e-var-triple? [vars where]
  (and (= 1 (count where))
       (let [[[type {:keys [e v]}]] where]
         (and (= :triple type)
              (contains? vars e)
              (logic-var? e)
              (literal? v)))))

;; TODO: For or (but not or-join) it might be possible to embed the
;; entire or expression into the parent join via either OrVirtualIndex
;; (though as all joins now are binary they have variable order
;; dependency so this might work easily) or NAryOrVirtualIndex for the
;; generic case. As constants are represented by relations, which
;; introduce new vars which would have to be lifted up to the parent
;; join as all or branches need to have the same variables. Another
;; problem when embedding joins are the sub joins constraints which
;; need to fire at the right level, but they won't currently know how
;; to translate their local join depth to the join depth in the
;; parent, which is what will be used when walking the tree. Due to
;; the way or-join (and rules) work, they likely have to stay as sub
;; queries. Recursive rules always have to be sub queries.
(defn- or-single-e-var-triple-fast-path [snapshot {:keys [business-time transact-time] :as db} where args]
  (let [[[_ {:keys [e a v] :as clause}]] where
        entity (get (first args) e)]
    (when (doc/or-known-triple-fast-path snapshot entity a v business-time transact-time)
      [[nil true]])))

(def ^:private ^:dynamic *recursion-table* {})

;; TODO: This tabling mechanism attempts at avoiding infinite
;; recursion, but does not actually cache anything. Short-circuits
;; identical sub trees. Passes tests, unsure if this really works in
;; the general case. Depends on the eager expansion of rules for some
;; cases to pass. One alternative is maybe to try to cache the
;; sequence and reuse it, somehow detecting if it loops.
(defn- build-or-constraints [rule-name->rules or-clause+idx-id+or-branches
                             var->bindings vars-in-join-order v-var->range-constriants]
  (for [[clause idx-id [{:keys [free-vars bound-vars]} :as or-branches]] or-clause+idx-id+or-branches
        :let [or-join-depth (calculate-constraint-join-depth var->bindings bound-vars)
              free-vars-in-join-order (filter (set free-vars) vars-in-join-order)
              has-free-vars? (boolean (seq free-vars))
              {:keys [rule-name]} (meta clause)]]
    (do (validate-existing-vars var->bindings clause bound-vars)
        {:join-depth or-join-depth
         :constraint-fn
         (fn or-constraint [snapshot {:keys [object-store] :as db} idx-id->idx join-keys join-results]
           (let [args (when (seq bound-vars)
                        [(->> (for [var bound-vars]
                                (:value (bound-results-for-var snapshot object-store var->bindings join-keys join-results var)))
                              (zipmap bound-vars))])
                 free-results+branch-matches?
                 (->> (for [[branch-index {:keys [where] :as or-branch}] (map-indexed vector or-branches)
                            :let [cache-key (when rule-name
                                              [rule-name branch-index (count free-vars) (set (mapv vals args))])]]
                        (or (when cache-key
                              (get *recursion-table* cache-key))
                            (binding [*recursion-table* (if cache-key
                                                          (assoc *recursion-table* cache-key [])
                                                          *recursion-table*)]
                              (with-open [snapshot (doc/new-cached-snapshot snapshot false)]
                                (if (single-e-var-triple? bound-vars where)
                                  (or-single-e-var-triple-fast-path snapshot db where args)
                                  (let [{:keys [n-ary-join
                                                var->bindings]} (build-sub-query snapshot db where args rule-name->rules)]
                                    (when-let [idx-seq (seq (doc/layered-idx->seq n-ary-join))]
                                      (if has-free-vars?
                                        (vec (for [[join-keys join-results] idx-seq]
                                               [(vec (for [var free-vars-in-join-order]
                                                       (:value (bound-results-for-var snapshot object-store var->bindings join-keys join-results var))))
                                                true]))
                                        [[nil true]]))))))))
                      (reduce into []))
                 free-results (->> (map first free-results+branch-matches?)
                                   (distinct)
                                   (vec))]
             (when (seq free-results+branch-matches?)
               (when has-free-vars?
                 (doc/update-relation-virtual-index! (get idx-id->idx idx-id) free-results (map v-var->range-constriants free-vars-in-join-order)))
               join-results)))})))

;; TODO: Unification could be improved by using dynamic relations
;; propagating knowledge from the first var to the next. See comment
;; about this at rewrite-self-join-triple-clause. Currently unification
;; has to scan the values and check them as they get bound and doesn't
;; fully carry its weight compared to normal predicates.
(defn- build-unification-constraints [unify-clauses var->bindings]
  (for [{:keys [op args]
         :as clause} unify-clauses
        :let [unification-vars (filter logic-var? args)
              unification-join-depth (calculate-constraint-join-depth var->bindings unification-vars)]]
    (do (validate-existing-vars var->bindings clause unification-vars)
        {:join-depth unification-join-depth
         :constraint-fn
         (fn unification-constraint [snapshot {:keys [object-store] :as db} idx-id->idx join-keys join-results]
           (let [values (for [arg args]
                          (if (logic-var? arg)
                            (let [{:keys [result-index]} (get var->bindings arg)]
                              (->> (get join-keys result-index)
                                   (sorted-set-by bu/bytes-comparator)))
                            (->> (map idx/value->bytes (doc/normalize-value arg))
                                 (into (sorted-set-by bu/bytes-comparator)))))]
             (when (case op
                     == (boolean (not-empty (apply set/intersection values)))
                     != (empty? (apply set/intersection values)))
               join-results)))})))

(defn- build-not-constraints [rule-name->rules not-type not-clauses var->bindings]
  (for [not-clause not-clauses
        :let [[not-vars not-clause] (case not-type
                                      :not [(:not-vars (collect-vars (normalize-clauses [[:not not-clause]])))
                                            not-clause]
                                      :not-join [(:args not-clause)
                                                 (:body not-clause)])
              not-vars (remove blank-var? not-vars)
              not-join-depth (calculate-constraint-join-depth var->bindings not-vars)]]
    (do (validate-existing-vars var->bindings not-clause not-vars)
        {:join-depth not-join-depth
         :constraint-fn
         (fn not-constraint [snapshot {:keys [object-store] :as db} idx-id->idx join-keys join-results]
           (with-open [snapshot (doc/new-cached-snapshot snapshot false)]
             (let [args (when (seq not-vars)
                          [(->> (for [var not-vars]
                                  (:value (bound-results-for-var snapshot object-store var->bindings join-keys join-results var)))
                                (zipmap not-vars))])
                   {:keys [n-ary-join]} (build-sub-query snapshot db not-clause args rule-name->rules)]
               (when (empty? (doc/layered-idx->seq n-ary-join))
                 join-results))))})))

(defn- constrain-join-result-by-constraints [snapshot db idx-id->idx depth->constraints join-keys join-results]
  (reduce
   (fn [results constraint]
     (when results
       (constraint snapshot db idx-id->idx join-keys results)))
   join-results
   (get depth->constraints (count join-keys))))

(defn- potential-bpg-pair-vars [g vars]
  (for [var vars
        pair-var (dep/transitive-dependents g var)]
    pair-var))

(defn- add-all-dependencies [g deps]
  (->> deps
       (reduce
        (fn [g [dependencies dependents]]
          (->> dependencies
               (reduce
                (fn [g dependency]
                  (->> dependents
                       (reduce
                        (fn [g dependent]
                          (dep/depend g dependent dependency))
                        g)))
                g)))
        g)))

(defn- calculate-join-order [pred-clauses or-clause+idx-id+or-branches var->joins arg-vars triple-join-deps]
  (let [g (dep/graph)
        g (->> (keys var->joins)
               (reduce
                (fn [g v]
                  (dep/depend g v ::root))
                g))
        g (add-all-dependencies g triple-join-deps)
        pred-deps (for [{:keys [pred return] :as pred-clause} pred-clauses
                        :let [pred-vars (filter logic-var? (:args pred))]]
                    [(into pred-vars (potential-bpg-pair-vars g pred-vars))
                     (if return
                       [return]
                       [])])
        or-deps (for [[_ _ [{:keys [free-vars bound-vars]}]] or-clause+idx-id+or-branches]
                  [(into bound-vars (potential-bpg-pair-vars g bound-vars))
                   free-vars])
        g (add-all-dependencies g (concat pred-deps or-deps))
        join-order (dep/topo-sort g)]
    (vec (remove #{::root} join-order))))

(defn- expand-rules [where rule-name->rules recursion-cache]
  (->> (for [[type clause :as sub-clause] where]
         (if (= :rule type)
           (let [rule-name (:name clause)
                 rules (get rule-name->rules rule-name)]
             (when-not rules
               (throw (IllegalArgumentException.
                       (str "Unknown rule: " (pr-str sub-clause)))))
             (let [rule-args+body (for [{:keys [head body]} rules]
                                    [(vec (concat (:bound-args head)
                                                  (:args head)))
                                     body])
                   [arity :as arities] (->> rule-args+body
                                            (map (comp count first))
                                            (distinct))]
               (when-not (= 1 (count arities))
                 (throw (IllegalArgumentException. (str "Rule definitions require same arity: " (pr-str rules)))))
               (when-not (= arity (count (:args clause)))
                 (throw (IllegalArgumentException.
                         (str "Rule invocation has wrong arity, expected: " arity " " (pr-str sub-clause)))))
               ;; TODO: the caches and expansion here needs
               ;; revisiting.
               (let [expanded-rules (for [[branch-index [rule-args body]] (map-indexed vector rule-args+body)
                                          :let [rule-arg->query-arg (zipmap rule-args (:args clause))
                                                body-vars (->> (collect-vars (normalize-clauses body))
                                                               (vals)
                                                               (reduce into #{}))
                                                body-var->hidden-var (zipmap body-vars
                                                                             (map gensym body-vars))]]
                                      (w/postwalk-replace (merge body-var->hidden-var rule-arg->query-arg) body))
                     cache-key [:seen-rules rule-name]
                     ;; TODO: Understand this, does this really work
                     ;; in the general case?
                     expanded-rules (if (zero? (long (get-in recursion-cache cache-key 0)))
                                      (for [expanded-rule expanded-rules
                                            :let [expanded-rule (expand-rules expanded-rule rule-name->rules
                                                                              (update-in recursion-cache cache-key (fnil inc 0)))]
                                            :when (seq expanded-rule)]
                                        expanded-rule)
                                      expanded-rules)]
                 (if (= 1 (count expanded-rules))
                   (first expanded-rules)
                   (when (seq expanded-rules)
                     [[:or-join
                       (with-meta
                         {:args (vec (filter logic-var? (:args clause)))
                          :body (vec (for [expanded-rule expanded-rules]
                                       [:and expanded-rule]))}
                         {:rule-name rule-name})]])))))
           [sub-clause]))
       (reduce into [])))

(defn- compile-sub-query [where arg-vars rule-name->rules]
  (let [where (expand-rules where rule-name->rules {})
        {triple-clauses :triple
         range-clauses :range
         pred-clauses :pred
         unify-clauses :unify
         not-clauses :not
         not-join-clauses :not-join
         or-clauses :or
         or-join-clauses :or-join
         :as type->clauses} (normalize-clauses where)
        {:keys [e-vars
                v-vars
                pred-return-vars]} (collect-vars type->clauses)
        v-var->e (->> (for [{:keys [e v] :as clause} triple-clauses
                            :when (logic-var? v)]
                        [v e])
                      (into {}))
        e->v-var (set/map-invert v-var->e)
        var->joins {}
        v-var->range-constriants (build-v-var-range-constraints e-vars range-clauses)
        v-range-vars (set (keys v-var->range-constriants))
        non-leaf-vars (set/union e-vars arg-vars v-range-vars)
        [triple-join-deps var->joins] (triple-joins triple-clauses
                                                    var->joins
                                                    non-leaf-vars
                                                    arg-vars)
        [args-idx-id var->joins] (arg-joins arg-vars
                                            e-vars
                                            v-var->range-constriants
                                            var->joins)
        [pred-clause+idx-ids var->joins] (pred-joins pred-clauses v-var->range-constriants var->joins)
        known-vars (set/union e-vars v-vars pred-return-vars arg-vars)
        [or-clause+idx-id+or-branches known-vars var->joins] (or-joins rule-name->rules
                                                                       :or
                                                                       or-clauses
                                                                       var->joins
                                                                       known-vars)
        [or-join-clause+idx-id+or-branches known-vars var->joins] (or-joins rule-name->rules
                                                                            :or-join
                                                                            or-join-clauses
                                                                            var->joins
                                                                            known-vars)
        or-clause+idx-id+or-branches (concat or-clause+idx-id+or-branches
                                             or-join-clause+idx-id+or-branches)
        v-var->attr (->> (for [{:keys [e a v]} triple-clauses
                               :when (and (logic-var? v)
                                          (= e (get v-var->e v)))]
                           [v a])
                         (into {}))
        e-var->attr (zipmap e-vars (repeat :crux.db/id))
        var->attr (merge e-var->attr v-var->attr)
        join-depth (count var->joins)
        vars-in-join-order (calculate-join-order pred-clauses or-clause+idx-id+or-branches var->joins arg-vars triple-join-deps)
        arg-vars-in-join-order (filter (set arg-vars) vars-in-join-order)
        var->values-result-index (zipmap vars-in-join-order (range))
        var->bindings (merge (build-or-free-var-bindings var->values-result-index or-clause+idx-id+or-branches)
                             (build-pred-return-var-bindings var->values-result-index pred-clauses)
                             (build-arg-var-bindings var->values-result-index arg-vars)
                             (build-var-bindings var->attr
                                                 v-var->e
                                                 e->v-var
                                                 var->values-result-index
                                                 join-depth
                                                 (keys var->attr)))
        unification-constraints (build-unification-constraints unify-clauses var->bindings)
        not-constraints (build-not-constraints rule-name->rules :not not-clauses var->bindings)
        not-join-constraints (build-not-constraints rule-name->rules :not-join not-join-clauses var->bindings)
        pred-constraints (build-pred-constraints pred-clause+idx-ids var->bindings)
        or-constraints (build-or-constraints rule-name->rules or-clause+idx-id+or-branches
                                             var->bindings vars-in-join-order v-var->range-constriants)
        depth->constraints (->> (concat unification-constraints
                                        pred-constraints
                                        not-constraints
                                        not-join-constraints
                                        or-constraints)
                                (reduce
                                 (fn [acc {:keys [join-depth constraint-fn]}]
                                   (update acc join-depth (fnil conj []) constraint-fn))
                                 (vec (repeat join-depth nil))))]
    {:depth->constraints depth->constraints
     :v-var->range-constriants v-var->range-constriants
     :vars-in-join-order vars-in-join-order
     :var->joins var->joins
     :var->bindings var->bindings
     :arg-vars-in-join-order arg-vars-in-join-order
     :args-idx-id args-idx-id}))

(defn- build-idx-id->idx [var->joins]
  (->> (for [[_ joins] var->joins
             {:keys [id idx-fn] :as join} joins
             :when id]
         [id idx-fn])
       (reduce
        (fn [acc [id idx-fn]]
          (if (contains? acc id)
            acc
            (assoc acc id (idx-fn))))
        {})))

(defn- build-sub-query [snapshot {:keys [kv query-cache object-store business-time transact-time] :as db} where args rule-name->rules]
  ;; NOTE: this implies argument sets with different vars get compiled
  ;; differently.
  (let [arg-vars (arg-vars args)
        {:keys [depth->constraints
                vars-in-join-order
                v-var->range-constriants
                var->joins
                var->bindings
                arg-vars-in-join-order
                args-idx-id]} (lru/compute-if-absent
                               query-cache
                               [where arg-vars rule-name->rules]
                               (fn [_]
                                 (compile-sub-query where arg-vars rule-name->rules)))
        idx-id->idx (build-idx-id->idx var->joins)
        constrain-result-fn (fn [join-keys join-results]
                              (constrain-join-result-by-constraints snapshot db idx-id->idx depth->constraints join-keys join-results))
        unary-join-index-groups (for [v vars-in-join-order]
                                  (for [{:keys [id idx-fn name] :as join} (get var->joins v)]
                                    (assoc (or (get idx-id->idx id) (idx-fn)) :name name)))]
    (doseq [[_ idx] idx-id->idx
            :when (instance? crux.doc.BinaryJoinLayeredVirtualIndex idx)]
      (update-binary-index! snapshot db idx vars-in-join-order v-var->range-constriants))
    (when (seq args)
      (doc/update-relation-virtual-index! (get idx-id->idx args-idx-id)
                                          (vec (for [arg args]
                                                 (mapv #(arg-for-var arg %) arg-vars-in-join-order)))
                                          (mapv v-var->range-constriants arg-vars-in-join-order)))
    (log/debug :where (pr-str where))
    (log/debug :vars-in-join-order vars-in-join-order)
    (log/debug :var->bindings (pr-str var->bindings))
    (constrain-result-fn [] [])
    {:n-ary-join (-> (mapv doc/new-unary-join-virtual-index unary-join-index-groups)
                     (doc/new-n-ary-join-layered-virtual-index)
                     (doc/new-n-ary-constraining-layered-virtual-index constrain-result-fn))
     :var->bindings var->bindings}))

;; NOTE: For ascending sort, it might be possible to pick the right
;; join order so the resulting seq is already sorted, by ensuring the
;; first vars of the join order overlap with the ones in order
;; by. Depending on the query this might not be possible. For example,
;; when using or-join/rules the order from the sub queries cannot be
;; guaranteed. The order by vars must be in the set of bound vars for
;; all or statements in the query for this to work. This is somewhat
;; related to embedding or in the main query. Also, this sort is based
;; on the actual values, and not the byte arrays, which would give
;; different sort order for example for ids, where the hash used in
;; the indexes won't sort the same as the actual value. For this to
;; work well this would need to be revisited.
(defn- order-by-comparator [vars order-by]
  (let [var->index (zipmap vars (range))]
    (reify Comparator
      (compare [_ a b]
        (loop [diff 0
               [{:keys [var direction]} & order-by] order-by]
          (if (or (not (zero? diff))
                  (nil? var))
            diff
            (let [index (get var->index var)]
              (recur (long (cond-> (compare (get a index)
                                            (get b index))
                             (= :desc direction) -))
                     order-by))))))))

(defn q
  ([{:keys [kv] :as db} q]
   (let [start-time (System/currentTimeMillis)]
     (with-open [snapshot (doc/new-cached-snapshot (ks/new-snapshot kv) true)]
       (let [result-coll-fn (if (:order-by q)
                              (comp vec distinct)
                              set)
             result (result-coll-fn (crux.query/q snapshot db q))]
         (log/debug :query-time-ms (- (System/currentTimeMillis) start-time))
         (log/debug :query-result-size (count result))
         result))))
  ([snapshot {:keys [object-store] :as db} q]
   (let [{:keys [find where args rules offset limit order-by] :as q} (s/conform :crux.query/query q)]
     (when (= :clojure.spec.alpha/invalid q)
       (throw (IllegalArgumentException.
               (str "Invalid input: " (s/explain-str :crux.query/query q)))))
     (validate-args args)
     (log/debug :query (pr-str q))
     (let [rule-name->rules (group-by (comp :name :head) rules)
           {:keys [n-ary-join
                   var->bindings]} (build-sub-query snapshot db where args rule-name->rules)]
       (doseq [var find
               :when (not (contains? var->bindings var))]
         (throw (IllegalArgumentException.
                 (str "Find refers to unknown variable: " var))))
       (cond->> (for [[join-keys join-results] (doc/layered-idx->seq n-ary-join)
                      :let [bound-result-tuple (for [var find]
                                                 (bound-results-for-var snapshot object-store var->bindings join-keys join-results var))]]
                  (with-meta
                    (mapv :value bound-result-tuple)
                    (zipmap (map :var bound-result-tuple) bound-result-tuple)))
         order-by (cio/external-sort (order-by-comparator find order-by))
         offset (drop offset)
         limit (take limit))))))

(defrecord QueryDatasource [kv query-cache object-store business-time transact-time])

(def ^:const default-await-tx-timeout 10000)

(defn- await-tx-time [kv transact-time ^long timeout]
  (let [timeout-at (+ timeout (System/currentTimeMillis))]
    (while (pos? (compare transact-time (doc/read-meta kv :crux.tx-log/tx-time)))
      (Thread/sleep 100)
      (when (>= (System/currentTimeMillis) timeout-at)
        (throw (IllegalStateException.
                (str "Timed out waiting for: " transact-time
                     " index has:" (doc/read-meta kv :crux.tx-log/tx-time))))))))

(def ^:const default-query-cache-size 10240)

(defn db
  ([kv]
   (let [business-time (cio/next-monotonic-date)]
     (->QueryDatasource kv
                        (doc/get-or-create-named-cache kv ::query-cache default-query-cache-size)
                        (doc/new-cached-object-store kv)
                        business-time
                        business-time)))
  ([kv business-time]
   (->QueryDatasource kv
                      (doc/get-or-create-named-cache kv ::query-cache default-query-cache-size)
                      (doc/new-cached-object-store kv)
                      business-time
                      (cio/next-monotonic-date)))
  ([kv business-time transact-time]
   (await-tx-time kv transact-time default-await-tx-timeout)
   (->QueryDatasource kv
                      (doc/get-or-create-named-cache kv ::query-cache default-query-cache-size)
                      (doc/new-cached-object-store kv)
                      business-time
                      transact-time)))
