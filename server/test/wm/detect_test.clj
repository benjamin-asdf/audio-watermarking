(ns wm.detect-test
  "Real audio through the real engine: register, embed with wmark, transcode, upload to /api/detect.
  Skipped when the wmark binary has not been built."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [wm.api :as api]
            [wm.api-test :as t]
            [wm.main :as main]))

(def wmark (str (.getCanonicalPath (io/file "../engine/wmark/target/release/wmark"))))

(use-fixtures :each t/reset-db)

(defn- sh! [& args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (assert (zero? exit) (str args err))
    out))

(defn- upload [system file]
  (let [resp ((api/handler system) {:request-method :post :uri "/api/detect" :headers {}
                                    :body (io/input-stream file)})]
    (json/read-value (slurp (:body resp)) json/keyword-keys-object-mapper)))

(deftest marked-mp3-is-verified
  (if-not (.exists (io/file wmark))
    (println "SKIP: build engine/wmark first (cargo build --release)")
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "wm-detect-" (System/nanoTime))) .mkdirs)
          f #(str (io/file dir %))
          system (assoc t/system :detect (partial main/wmark-detect wmark))
          session (t/login! "artist@example.org")
          {:keys [work-id payload-hex]} (:json (t/call :post "/api/works" t/record session))]
      (sh! "ffmpeg" "-nostdin" "-v" "error" "-y" "-f" "lavfi" "-i"
           "sine=frequency=330:duration=25,volume=0.3[a];anoisesrc=d=25:a=0.05:c=pink[b];[a][b]amix=inputs=2"
           "-ac" "2" "-ar" "44100" (f "song.wav"))
      (let [report (json/read-value (sh! wmark "embed" (f "song.wav") (f "song.marked.flac") payload-hex)
                                    json/keyword-keys-object-mapper)]
        (is (:verified report))
        (is (= 204 (:status (t/call :post (str "/api/works/" work-id "/finalize")
                                    {:fingerprint (:fingerprint report) :audio-sha256 (:sha256 report)}
                                    session)))))
      (sh! "ffmpeg" "-nostdin" "-v" "error" "-y" "-ss" "6" "-t" "12" "-i" (f "song.marked.flac")
           "-b:a" "128k" (f "clip.mp3"))
      (testing "marked clip"
        (let [r (upload system (f "clip.mp3"))]
          (is (= "verified" (:status r)))
          (is (= "Song" (get-in r [:work :record :title])))))
      (testing "unmarked original"
        (is (= "no-watermark" (:status (upload system (f "song.wav")))))))))
