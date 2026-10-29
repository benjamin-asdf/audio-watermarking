(ns wm.fingerprint
  "Chromaprint fingerprints (fpcalc -raw: one 32-bit int per ~0.124 s frame).
  Used to check that a clip carrying a watermark is actually the registered recording."
  (:require [clojure.java.shell :as sh]
            [jsonista.core :as json]))

(def frame-seconds 0.1238)

(defn compute
  "Runs fpcalc on a file. Returns a vector of ints."
  [path]
  (let [{:keys [exit out err]} (sh/sh "fpcalc" "-raw" "-json" "-length" "0" (str path))]
    (when-not (zero? exit)
      (throw (ex-info "fpcalc failed" {:err err})))
    (vec (get (json/read-value out) "fingerprint"))))

(defn- bit-errors
  ^long [^long a ^long b]
  (Long/bitCount (bit-and 0xffffffff (bit-xor a b))))

(defn best-alignment
  "Slides the clip over the reference. Returns {:offset :ber :overlap} for the offset with the
  lowest bit error rate among offsets that overlap at least min-overlap frames, or nil."
  ([reference clip] (best-alignment reference clip 24))
  ([reference clip min-overlap]
   (let [r (long-array reference)
         c (long-array clip)
         nr (alength r)
         nc (alength c)]
     (reduce
      (fn [best offset]
        (let [start (max 0 (- offset))
              end (min nc (- nr offset))
              overlap (- end start)]
          (if (< overlap min-overlap)
            best
            (let [errors (loop [i start acc 0]
                           (if (< i end)
                             (recur (inc i) (+ acc (bit-errors (aget c i) (aget r (+ i offset)))))
                             acc))
                  ber (/ (double errors) (* 32 overlap))]
              (if (or (nil? best) (< ber (:ber best)))
                {:offset offset :ber ber :overlap overlap}
                best)))))
      nil
      (range (- (dec nc)) nr)))))

;; Unrelated audio sits near 0.5 BER; the same recording after lossy coding is typically
;; well under 0.3. 0.35 leaves room for heavy transcoding.
(def match-threshold 0.35)

(defn same-recording?
  [reference clip]
  (if-let [{:keys [ber]} (best-alignment reference clip)]
    (< ber match-threshold)
    false))
