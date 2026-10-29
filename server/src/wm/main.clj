(ns wm.main
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [jsonista.core :as json]
            [org.httpkit.server :as http]
            [wm.api :as api]
            [wm.auth :as auth]
            [wm.db :as db]
            [wm.mail :as mail]
            [wm.ots :as ots]
            [wm.tsa :as tsa]
            [wm.works :as works])
  (:import (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)
           (java.util.concurrent Executors Semaphore TimeUnit)))

(defn- env [k default] (or (System/getenv k) default))

(def default-data-dir (str (System/getProperty "user.home") "/.local/share/audio-watermarking"))

(defn load-secret
  "HMAC secret for payload tags. Created (chmod 600) on first start if missing. Never logged."
  ^bytes [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (io/make-parents f)
      (.createNewFile f)
      ;; Restrict before writing, so the secret is never world-readable.
      (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rw-------"))
      (spit f (auth/random-token)))
    (.getBytes (str/trim (slurp f)) "UTF-8")))

(defn wmark-detect
  "Runs the engine CLI on a file. Returns {:candidates [hex ...] :fingerprint [int ...]}."
  [wmark-cmd file]
  (let [{:keys [exit out]} (sh/sh wmark-cmd "detect" (str file))
        r (json/read-value out json/keyword-keys-object-mapper)]
    (when-not (zero? exit)
      (throw (ex-info "wmark detect failed" {:error (:error r)})))
    {:candidates (mapv :payload (:candidates r))
     :fingerprint (:fingerprint r)}))

(defn wmark-embed
  "Marks input into output (lossless, by extension). Returns {:verified :fingerprint :sha256}."
  [wmark-cmd input output payload-hex]
  (let [{:keys [exit out]} (sh/sh wmark-cmd "embed" (str input) (str output) payload-hex)
        r (json/read-value out json/keyword-keys-object-mapper)]
    (when-not (zero? exit)
      (throw (ex-info "wmark embed failed" {:error (:error r)})))
    (select-keys r [:verified :fingerprint :sha256])))

(defn log-login-link
  "Dev mail transport: prints the magic link instead of sending it."
  [{:keys [email link]}]
  (println "LOGIN LINK for" email ":" link))

(defn login-transport
  "SMTP when WM_SMTP_HOST is set, else the log. Addresses under .invalid (RFC 2606, never
  deliverable) always go to the log: the smoke test reads its link from there."
  []
  (if-let [host (env "WM_SMTP_HOST" nil)]
    (let [cfg {:host host
               :port (parse-long (env "WM_SMTP_PORT" "587"))
               :user (env "WM_SMTP_USER" nil)
               :password (env "WM_SMTP_PASSWORD" nil)
               :from (env "WM_MAIL_FROM" nil)}]
      (fn [{:keys [email] :as login}]
        (if (str/ends-with? email ".invalid")
          (log-login-link login)
          (do (mail/send-login cfg login)
              (println "login mail sent to" email)))))
    log-login-link))

(defn system
  []
  (let [ds (db/datasource (env "WM_DB_URL" "jdbc:postgresql://localhost:5439/wm?user=wm"))
        pool (Executors/newFixedThreadPool 2)]
    {:ds ds
     :pool pool
     :secret (load-secret (env "WM_SECRET_FILE" (str default-data-dir "/payload-secret")))
     :engine (keyword (env "WM_ENGINE" "earmark"))
     :public-url (env "WM_PUBLIC_URL" "http://localhost:8090")
     ;; Mount prefix behind a shared reverse proxy, e.g. "/earmark"; WM_PUBLIC_URL includes it.
     :base-path (str/replace (env "WM_BASE_PATH" "") #"/$" "")
     ;; Extra CORS origins, comma separated. Dev: http://localhost:1430 for `cargo tauri dev`.
     :cors-origins (remove str/blank? (str/split (env "WM_CORS_ORIGINS" "") #"\s*,\s*"))
     :send-login (login-transport)
     :stamp ots/stamp
     ;; RFC 3161 TSAs, tried in order. Comma separated override: WM_TSA_URLS.
     :tsa-stamp (partial tsa/stamp (if-let [u (env "WM_TSA_URLS" nil)]
                                     (str/split u #"\s*,\s*")
                                     tsa/default-urls))
     :upgrade ots/upgrade
     :stamp-async (fn [f] (.submit pool ^Runnable f))
     :detect (partial wmark-detect (env "WM_WMARK_CMD" "wmark"))
     :embed (partial wmark-embed (env "WM_WMARK_CMD" "wmark"))
     ;; Website marking: one job at a time, the box is small.
     :embed-lock (Semaphore. 1)}))

(defn -main
  [& _]
  (let [{:keys [ds] :as sys} (system)
        port (parse-long (env "WM_PORT" "8090"))]
    (db/migrate! ds)
    (doto (Executors/newSingleThreadScheduledExecutor)
      (.scheduleWithFixedDelay #(do (try (works/upgrade-pending-proofs! sys)
                                         (catch Exception e (println "ots upgrade job:" (ex-message e))))
                                    (try (works/stamp-missing-tsa! sys)
                                         (catch Exception e (println "tsa backfill job:" (ex-message e)))))
                               1 30 TimeUnit/MINUTES))
    (http/run-server (api/handler sys) {:ip (env "WM_BIND" "127.0.0.1") :port port :max-body api/max-upload-bytes})
    (println "listening on" port)))
