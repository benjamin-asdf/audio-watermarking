(ns wm.db
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql])
  (:import (java.sql Array)
           (org.postgresql.util PGobject)))

(def opts {:builder-fn rs/as-unqualified-kebab-maps})

(defn datasource
  [jdbc-url]
  (jdbc/get-datasource {:jdbcUrl jdbc-url}))

(defn migrate!
  [ds]
  (doseq [stmt (str/split (slurp (io/resource "schema.sql")) #";\s*\n")
          :when (not (str/blank? stmt))]
    (jdbc/execute! ds [stmt])))

(defn jsonb
  [x]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-value-as-string x))))

(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [^PGobject v _]
    (if (#{"jsonb" "json"} (.getType v))
      (json/read-value (.getValue v) json/keyword-keys-object-mapper)
      (.getValue v)))
  (read-column-by-index [^PGobject v _ _]
    (if (#{"jsonb" "json"} (.getType v))
      (json/read-value (.getValue v) json/keyword-keys-object-mapper)
      (.getValue v)))
  Array
  (read-column-by-label [^Array v _] (vec (.getArray v)))
  (read-column-by-index [^Array v _ _] (vec (.getArray v))))

(defn int-array-param
  [^java.sql.Connection conn xs]
  ;; fpcalc emits unsigned 32-bit values; store them wrapped to signed (comparison masks to 32 bits).
  (.createArrayOf conn "integer" (to-array (map unchecked-int xs))))

(defn q
  [ds sqlvec]
  (jdbc/execute! ds sqlvec opts))

(defn q1
  [ds sqlvec]
  (jdbc/execute-one! ds sqlvec opts))

(defn insert!
  [ds table row]
  (sql/insert! ds table row opts))
