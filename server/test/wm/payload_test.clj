(ns wm.payload-test
  (:require [clojure.test :refer [deftest is testing]]
            [wm.payload :as p]))

(def secret (.getBytes "test-secret" "UTF-8"))

(deftest round-trip
  (doseq [[engine layout] p/layouts]
    (testing (name engine)
      (dotimes [_ 200]
        (let [id (p/random-work-id layout)
              hex (p/encode layout secret id)]
          (is (= (quot (:bits layout) 4) (count hex)))
          (is (= {:version p/version :work-id id :valid? true}
                 (p/decode layout secret hex))))))))

(deftest rejects-forged-and-garbled
  (let [layout (p/layouts :audiowmark)
        hex (p/encode layout secret 12345)
        flip (fn [s i] (let [c (Character/digit (.charAt s i) 16)]
                         (str (subs s 0 i) (Integer/toHexString (bit-xor c 1)) (subs s (inc i)))))]
    (testing "any single flipped hex digit invalidates the tag"
      (doseq [i (range (count hex))]
        (is (false? (:valid? (p/decode layout secret (flip hex i)))))))
    (testing "other secret"
      (is (false? (:valid? (p/decode layout (.getBytes "other" "UTF-8") hex)))))
    (testing "malformed"
      (is (nil? (p/decode layout secret "abc")))
      (is (nil? (p/decode layout secret (apply str (repeat 32 "z"))))))))
