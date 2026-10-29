(ns wm.payload
  "The bits we hide in the audio: | version | work-id | tag |.
  tag = truncated HMAC-SHA256(secret, version || work-id), so the server can reject payloads it
  never issued (invented or garbled) without touching the database.

  Layouts differ per engine because capacity differs (audiowmark 128 bit, SilentCipher 40 bit)."
  (:import (java.security SecureRandom)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def layouts
  ;; id-bits <= 63 so work-ids fit a signed Postgres bigint.
  ;; earmark (our engine): 96 bits = more redundancy per bit. With a detector list of <= 64
  ;; candidates, a 48-bit tag still gives ~64 * 2^-48 false accepts per lookup.
  {:earmark {:bits 96 :version-bits 4 :id-bits 44}
   :audiowmark {:bits 128 :version-bits 4 :id-bits 63}
   :silentcipher {:bits 40 :version-bits 2 :id-bits 24}})

(def version 1)

(defn tag-bits
  [{:keys [bits version-bits id-bits]}]
  (- bits version-bits id-bits))

(defn- hmac
  ^bytes [^bytes secret ^bytes data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. secret "HmacSHA256"))
    (.doFinal mac data)))

(defn- tag
  ^BigInteger [layout secret ver work-id]
  (let [data (.getBytes (str ver ":" work-id) "UTF-8")
        full (BigInteger. 1 (hmac secret data))]
    (.shiftRight full (- 256 (tag-bits layout)))))

(defn random-work-id
  "Uniform in [1, 2^id-bits). 0 is reserved as 'no id'."
  [{:keys [id-bits]}]
  (let [rng (SecureRandom.)]
    (loop []
      (let [n (BigInteger. (int id-bits) rng)]
        (if (pos? (.signum n)) (long n) (recur))))))

(defn hex
  "Fixed-width lowercase hex of the payload integer."
  [layout ^BigInteger n]
  (let [s (.toString n 16)
        width (quot (:bits layout) 4)]
    (str (apply str (repeat (- width (count s)) \0)) s)))

(defn encode
  "Payload hex for work-id under layout."
  [layout secret work-id]
  (let [{:keys [id-bits]} layout
        tb (tag-bits layout)
        n (-> (biginteger version)
              (.shiftLeft (int id-bits))
              (.or (biginteger work-id))
              (.shiftLeft (int tb))
              (.or (tag layout secret version work-id)))]
    (hex layout n)))

(defn decode
  "Returns {:version :work-id :valid?} for a payload hex string, or nil if malformed.
  :valid? is false when the tag does not match (payload we never issued)."
  [layout secret payload-hex]
  (when (and (string? payload-hex)
             (= (count payload-hex) (quot (:bits layout) 4))
             (re-matches #"[0-9a-fA-F]+" payload-hex))
    (let [{:keys [id-bits version-bits]} layout
          tb (tag-bits layout)
          n (BigInteger. ^String payload-hex 16)
          mask (fn [bits] (.subtract (.shiftLeft BigInteger/ONE (int bits)) BigInteger/ONE))
          t (.and n (mask tb))
          work-id (long (.and (.shiftRight n (int tb)) (mask id-bits)))
          ver (long (.and (.shiftRight n (int (+ tb id-bits))) (mask version-bits)))]
      {:version ver
       :work-id work-id
       :valid? (and (= ver version)
                    (= t (tag layout secret ver work-id)))})))
