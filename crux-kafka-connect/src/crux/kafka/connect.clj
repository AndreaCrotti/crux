(ns crux.kafka.connect
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [crux.codec :as c])
  (:import [org.apache.kafka.connect.data Schema Struct]
           org.apache.kafka.connect.sink.SinkRecord
           [java.util UUID Map]
           crux.kafka.connect.CruxSinkConnector
           crux.api.ICruxAPI))

(defn- map->edn [m]
  (->> (for [[k v] m]
         [(keyword k)
          (if (instance? Map v)
            (map->edn v)
            v)])
       (into {})))

(defn- struct->edn [^Schema schema ^Struct s]
  (throw (UnsupportedOperationException. "Struct conversion not yet supported.")))

(defn- record->edn [^SinkRecord record]
  (let [schema (.valueSchema record)
        value (.value record)]
    (cond
      (and (instance? Struct value) schema)
      (struct->edn schema value)

      (and (instance? Map value)
           (nil? schema)
           (= #{"payload" "schema"} (set (keys value))))
      (let [payload (.get ^Map value "payload")]
        (cond
          (string? payload)
          (json/parse-string payload true)

          (instance? Map payload)
          (map->edn payload)

          :else
          (throw (IllegalArgumentException. (str "Unknown JSON payload type: " record)))))

      (instance? Map value)
      (map->edn value)

      (string? value)
      (json/parse-string value true)

      :else
      (throw (IllegalArgumentException. (str "Unknown message type: " record))))))

(defn- find-eid [props ^SinkRecord record doc]
  (let [id (or (get doc :crux.db/id)
               (some->> (get props CruxSinkConnector/ID_KEY_CONFIG)
                        (keyword)
                        (get doc))
               (.key record))]
    (cond
      (and id (c/valid-id? id))
      (c/id-edn-reader id)
      (string? id)
      (keyword id)
      :else
      (UUID/randomUUID))))

(defn transform-sink-record [props ^SinkRecord record]
  (log/info "sink record:" record)
  (let [doc (record->edn record)
        id (find-eid props record doc)
        tx-op [:crux.tx/put (assoc doc :crux.db/id id)]]
    (log/info "tx op:" tx-op)
    tx-op))

(defn submit-sink-records [^ICruxAPI api props records]
  (when (seq records)
    (.submitTx api (vec (for [record records]
                          (transform-sink-record props record))))))