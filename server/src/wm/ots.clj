(ns wm.ots
  "OpenTimestamps via the `ots` CLI (pip opentimestamps-client).
  stamp: submits a SHA-256 to the public calendars, returns the pending .ots proof bytes.
  upgrade: once a Bitcoin block includes the calendar's commitment (~hours), the proof becomes
  self-contained and verifiable without trusting us or the calendars."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- with-temp-dir
  [f]
  (let [dir (.toFile (Files/createTempDirectory "wm-ots" (make-array FileAttribute 0)))]
    (try (f dir)
         (finally (doseq [x (reverse (file-seq dir))] (io/delete-file x true))))))

(defn- read-bytes
  [file]
  (Files/readAllBytes (.toPath (io/file file))))

(defn stamp
  "Stamps the given bytes (we stamp the canonical record JSON). Returns .ots bytes."
  [^bytes data]
  (with-temp-dir
    (fn [dir]
      (let [f (io/file dir "record.json")]
        (io/copy data f)
        (let [{:keys [exit err]} (sh/sh "ots" "stamp" (str f))]
          (when-not (zero? exit) (throw (ex-info "ots stamp failed" {:err err}))))
        (read-bytes (io/file dir "record.json.ots"))))))

(defn upgrade
  "Returns {:complete? bool :ots bytes}. Pending proofs come back unchanged."
  [^bytes data ^bytes ots]
  (with-temp-dir
    (fn [dir]
      (let [f (io/file dir "record.json")
            p (io/file dir "record.json.ots")]
        (io/copy data f)
        (io/copy ots p)
        (let [{:keys [exit]} (sh/sh "ots" "upgrade" (str p))]
          {:complete? (zero? exit) :ots (read-bytes p)})))))
