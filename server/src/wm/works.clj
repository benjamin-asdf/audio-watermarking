(ns wm.works
  "Registering, finalizing, editing and looking up marked works."
  (:require [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [wm.db :as db]
            [wm.fingerprint :as fp]
            [wm.payload :as payload]
            [wm.tsa :as tsa])
  (:import (java.security MessageDigest)
           (java.util HexFormat)))

(def Record
  [:map {:closed true}
   [:artist [:string {:min 1 :max 200}]]
   [:title [:string {:min 1 :max 300}]]
   [:isrc {:optional true} [:re #"^[A-Z]{2}[A-Z0-9]{3}\d{7}$"]]
   [:links {:optional true} [:vector {:max 10} [:re #"^https?://\S{1,500}$"]]]
   [:contact {:optional true} [:string {:max 300}]]
   [:license {:optional true} [:string {:max 1000}]]])

(defn- canonical
  "Deterministic JSON bytes of a record (sorted keys) -- this is what gets timestamped."
  ^bytes [record]
  (json/write-value-as-bytes (into (sorted-map) record)))

(defn- sha256-hex
  [^bytes b]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") b)))

(defn- insert-version!
  [ds work-id version record]
  (let [c (canonical record)]
    (db/q1 ds ["INSERT INTO work_versions (work_id, version, record, record_canonical, record_sha256)
                VALUES (?, ?, ?, ?, ?)" work-id version (db/jsonb record) c (sha256-hex c)])))

(defn create!
  "Allocates a work-id and payload for the account. Returns {:work-id :payload-hex :engine :bits}."
  [{:keys [ds secret engine]} account-id record]
  (let [layout (payload/layouts engine)]
    (loop [attempt 0]
      (let [work-id (payload/random-work-id layout)
            hex (payload/encode layout secret work-id)
            inserted (jdbc/with-transaction [tx ds]
                       (when (db/q1 tx ["INSERT INTO works (id, account_id, engine, payload_hex)
                                         VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id"
                                        work-id account-id (name engine) hex])
                         (insert-version! tx work-id 1 record)
                         true))]
        ;; work-ids exceed JS safe integers, so they travel as strings.
        (cond inserted {:work-id (str work-id) :payload-hex hex :engine (name engine) :bits (:bits layout)}
              (< attempt 5) (recur (inc attempt))
              :else (throw (ex-info "work-id space exhausted?" {})))))))

(defn- latest-version
  [ds work-id]
  (db/q1 ds ["SELECT version, record, record_canonical, created_at, ots_status FROM work_versions
              WHERE work_id = ? ORDER BY version DESC LIMIT 1" work-id]))

(defn stamp-version!
  "Timestamps one record version via (:stamp system). Failures are recorded, not thrown."
  [{:keys [ds stamp]} work-id version]
  (let [{:keys [record-canonical]} (db/q1 ds ["SELECT record_canonical FROM work_versions
                                               WHERE work_id = ? AND version = ?" work-id version])]
    (try
      (db/q1 ds ["UPDATE work_versions SET ots = ?, ots_status = 'pending'
                  WHERE work_id = ? AND version = ?" (stamp record-canonical) work-id version])
      (catch Exception e
        (db/q1 ds ["UPDATE work_versions SET ots_status = 'failed' WHERE work_id = ? AND version = ?"
                   work-id version])
        (println "ots stamp failed" work-id version (ex-message e))))))

(defn tsa-stamp-version!
  "Signed RFC 3161 timestamp for one record version via (:tsa-stamp system). Synchronous (one HTTP
  round trip); failures are logged and retried by stamp-missing-tsa!."
  [{:keys [ds tsa-stamp]} work-id version]
  (let [{:keys [record-canonical]} (db/q1 ds ["SELECT record_canonical FROM work_versions
                                               WHERE work_id = ? AND version = ?" work-id version])]
    (try
      (let [{:keys [response time url]} (tsa-stamp record-canonical)]
        (db/q1 ds ["UPDATE work_versions SET tsa_response = ?, tsa_url = ?, tsa_time = ?
                    WHERE work_id = ? AND version = ?"
                   response url (java.sql.Timestamp. (.getTime ^java.util.Date time)) work-id version]))
      (catch Exception e
        (println "tsa stamp failed" work-id version (ex-message e) (pr-str (:errors (ex-data e))))))))

(defn- owned
  [ds account-id work-id]
  (db/q1 ds ["SELECT * FROM works WHERE id = ? AND account_id = ?" work-id account-id]))

(defn pending-own
  "The caller's work if it still waits for its audio, else nil."
  [ds account-id work-id]
  (let [w (owned ds account-id work-id)]
    (when (= "pending" (:status w)) w)))

(defn finalize!
  "Called by the app after it marked and self-verified the audio locally."
  [{:keys [ds stamp-async] :as system} account-id work-id {:keys [fingerprint audio-sha256]}]
  (when-let [w (owned ds account-id work-id)]
    (when (= "pending" (:status w))
      (jdbc/with-transaction [tx ds]
        (db/q1 tx ["UPDATE works SET status = 'final', finalized_at = now(),
                    fingerprint = ?, audio_sha256 = ? WHERE id = ?"
                   (db/int-array-param tx fingerprint) audio-sha256 work-id]))
      (tsa-stamp-version! system work-id 1)
      (stamp-async #(stamp-version! system work-id 1)))
    true))

(defn update-record!
  [{:keys [ds stamp-async] :as system} account-id work-id record]
  (when (owned ds account-id work-id)
    (let [v (inc (:version (latest-version ds work-id)))]
      (insert-version! ds work-id v record)
      (tsa-stamp-version! system work-id v)
      (stamp-async #(stamp-version! system work-id v))
      v)))

(defn public-view
  [ds work-id]
  (when-let [w (db/q1 ds ["SELECT id, status, created_at, finalized_at FROM works
                           WHERE id = ? AND status = 'final'" work-id])]
    (let [versions (db/q ds ["SELECT version, record_sha256, ots_status, created_at, tsa_url, tsa_time
                              FROM work_versions WHERE work_id = ? ORDER BY version" work-id])]
      {:work-id (str (:id w))
       :registered-at (str (:finalized-at w))
       :record (:record (latest-version ds work-id))
       :versions (mapv (fn [{:keys [tsa-url tsa-time] :as v}]
                         (-> (dissoc v :tsa-url :tsa-time)
                             (update :created-at str)
                             (cond-> tsa-time (assoc :tsa-time (str (.toInstant ^java.sql.Timestamp tsa-time))
                                                     :tsa-name (tsa/tsa-name tsa-url)))))
                       versions)})))

(defn tsa-proof
  [ds work-id version]
  (:tsa-response (db/q1 ds ["SELECT tsa_response FROM work_versions WHERE work_id = ? AND version = ?"
                            work-id version])))

(defn record-bytes
  "The exact bytes both proofs cover (canonical JSON of the record), for anyone verifying them."
  [ds work-id version]
  (:record-canonical (db/q1 ds ["SELECT v.record_canonical FROM work_versions v
                                 JOIN works w ON w.id = v.work_id AND w.status = 'final'
                                 WHERE v.work_id = ? AND v.version = ?" work-id version])))

(defn ots-proof
  [ds work-id version]
  (:ots (db/q1 ds ["SELECT ots FROM work_versions WHERE work_id = ? AND version = ?" work-id version])))

(defn list-own
  [ds account-id]
  (->> (db/q ds ["SELECT w.id, w.status, w.payload_hex, w.created_at, v.record
                  FROM works w JOIN LATERAL (SELECT record FROM work_versions
                    WHERE work_id = w.id ORDER BY version DESC LIMIT 1) v ON true
                  WHERE w.account_id = ? ORDER BY w.created_at DESC" account-id])
       (mapv #(-> % (update :id str) (update :created-at str)))))

(defn lookup
  "Status for detector candidates (audiowmark reports noise candidates even for unmarked audio;
  only a payload whose HMAC tag checks out counts), optionally checked against the clip's
  fingerprint: :no-watermark | :unknown-id | :id-found (no fingerprint given) | :verified |
  :audio-differs"
  [{:keys [ds secret engine]} candidates clip-fingerprint]
  (let [layout (payload/layouts engine)
        work-id (some (fn [hex] (let [{:keys [valid? work-id]} (payload/decode layout secret hex)]
                                  (when valid? work-id)))
                      candidates)
        w (when work-id (db/q1 ds ["SELECT id, fingerprint FROM works WHERE id = ? AND status = 'final'"
                                   work-id]))]
    (cond
      (nil? work-id) {:status :no-watermark}
      (nil? w) {:status :unknown-id}
      (empty? clip-fingerprint) {:status :id-found :work (public-view ds work-id)}
      :else {:status (if (fp/same-recording? (:fingerprint w) clip-fingerprint) :verified :audio-differs)
             :work (public-view ds work-id)})))

(defn upgrade-pending-proofs!
  "Periodic job: turns pending OTS proofs into complete ones once Bitcoin has confirmed."
  [{:keys [ds upgrade]}]
  (doseq [{:keys [work-id version record-canonical ots]}
          (db/q ds ["SELECT work_id, version, record_canonical, ots FROM work_versions
                     WHERE ots_status = 'pending'"])]
    (let [{:keys [complete? ots]} (upgrade record-canonical ots)]
      (db/q1 ds ["UPDATE work_versions SET ots = ?, ots_status = ? WHERE work_id = ? AND version = ?"
                 ots (if complete? "complete" "pending") work-id version]))))

(defn stamp-missing-tsa!
  "Periodic job: signed timestamps for final works whose versions have none (TSA was down, or the
  version predates RFC 3161 stamping)."
  [{:keys [ds] :as system}]
  (doseq [{:keys [work-id version]}
          (db/q ds ["SELECT v.work_id, v.version FROM work_versions v
                     JOIN works w ON w.id = v.work_id AND w.status = 'final'
                     WHERE v.tsa_response IS NULL"])]
    (tsa-stamp-version! system work-id version)))
