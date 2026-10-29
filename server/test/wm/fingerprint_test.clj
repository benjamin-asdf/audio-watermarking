(ns wm.fingerprint-test
  (:require [clojure.test :refer [deftest is]]
            [wm.fingerprint :as fp]))

(defn- random-fp [seed n]
  (let [r (java.util.Random. seed)]
    (vec (repeatedly n #(.nextInt r)))))

(defn- noisy
  "Flips each bit with probability p."
  [fingerprint p seed]
  (let [r (java.util.Random. seed)]
    (mapv (fn [x] (reduce (fn [acc bit] (if (< (.nextDouble r) p) (bit-flip acc bit) acc)) x (range 32)))
          fingerprint)))

(deftest finds-clip-offset
  (let [reference (random-fp 1 1500)
        clip (subvec reference 400 480)]
    (is (= {:offset 400 :ber 0.0 :overlap 80} (fp/best-alignment reference clip)))))

(deftest tolerates-transcoding-noise
  (let [reference (random-fp 1 1500)]
    (is (fp/same-recording? reference (noisy (subvec reference 700 800) 0.15 7)))))

(deftest rejects-other-recording
  (is (not (fp/same-recording? (random-fp 1 1500) (random-fp 2 80)))))
