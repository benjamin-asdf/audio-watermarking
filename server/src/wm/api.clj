(ns wm.api
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.util :as mu]
            [muuntaja.core :as m]
            [reitit.coercion.malli :as rcm]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [wm.auth :as auth]
            [wm.works :as works])
  (:import (java.io File FileInputStream)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def WorkId [:re #"^\d{1,20}$"])

(defn- work-id [request] (parse-long (get-in request [:parameters :path :id])))

(defn- not-found [] {:status 404 :body {:error "not found"}})

(defn- download
  "Route data for a per-version file: (fetch ds work-id version) -> bytes or nil."
  [ds fetch content-type filename]
  {:parameters {:path [:map [:id WorkId] [:v :int]]}
   :get {:handler (fn [r]
                    (if-let [b (fetch ds (work-id r) (get-in r [:parameters :path :v]))]
                      {:status 200
                       :headers {"Content-Type" content-type
                                 "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
                       :body (java.io.ByteArrayInputStream. b)}
                      (not-found)))}})

(defn- wrap-account
  "Requires a valid bearer session; puts :account-id on the request."
  [handler system]
  (fn [request]
    (if-let [account-id (auth/session-account system (auth/bearer-token request))]
      (handler (assoc request :account-id account-id))
      {:status 401 :body {:error "login required"}})))

(def max-upload-bytes (* 300 1024 1024))

(defn- detect-upload
  "Website read flow: audio is processed in a temp file and deleted, never stored."
  [{:keys [detect] :as system} request]
  (let [tmp (File/createTempFile "wm-upload" ".audio")]
    (try
      (with-open [in (:body request)]
        (io/copy in tmp))
      (if (> (.length tmp) max-upload-bytes)
        {:status 413 :body {:error "file too large"}}
        (let [{:keys [candidates fingerprint]} (detect tmp)]
          {:status 200 :body (works/lookup system candidates fingerprint)}))
      (finally (.delete tmp)))))

(defn- delete-tree
  [^File dir]
  (doseq [f (reverse (file-seq dir))] (.delete ^File f)))

(defn- safe-ext
  [name]
  (or (some->> name (re-find #"\.([A-Za-z0-9]{1,5})$") second str/lower-case) "audio"))

(defn- download-name
  [name fmt]
  (str (or (some-> name (str/replace #"\.[A-Za-z0-9]{1,5}$" "") (str/replace #"[^\w .()-]" "_") not-empty)
           "track")
       ".marked." fmt))

(defn- embed-upload
  "Website mark flow: the upload is marked in a temp dir, the work finalized and timestamped, the
  marked copy streamed back. Both files are unlinked before the response goes out (the open stream
  keeps the data until it is closed), so nothing stays on disk. One job at a time."
  [{:keys [ds embed embed-lock] :as system} {:keys [account-id body] :as r}]
  (let [id (work-id r)
        {:keys [format name]} (get-in r [:parameters :query])
        fmt (or format "flac")
        w (works/pending-own ds account-id id)]
    (cond
      (nil? w) (do (some-> body .close) (not-found))
      (not (.tryAcquire ^java.util.concurrent.Semaphore embed-lock))
      (do (some-> body .close) {:status 429 :body {:error "Another track is being marked right now. Try again in a minute."}})
      :else
      (let [dir (.toFile (Files/createTempDirectory "wm-embed" (make-array FileAttribute 0)))]
        (try
          (let [in (io/file dir (str "input." (safe-ext name)))
                out (io/file dir (str "marked." fmt))]
            (with-open [b body] (io/copy b in))
            (if (> (.length in) max-upload-bytes)
              {:status 413 :body {:error "file too large"}}
              (let [rep (try (embed in out (:payload-hex w))
                             (catch clojure.lang.ExceptionInfo e {:error (or (:error (ex-data e)) (ex-message e))}))]
                (cond
                  (:error rep) {:status 422 :body {:error (str "Could not mark this file: " (:error rep))}}
                  (not (:verified rep)) {:status 422 :body {:error "The mark could not be read back from the result. Try a longer or louder track."}}
                  :else
                  (let [stream (FileInputStream. out)]
                    (works/finalize! system account-id id {:fingerprint (:fingerprint rep) :audio-sha256 (:sha256 rep)})
                    {:status 200
                     :headers {"Content-Type" (if (= fmt "wav") "audio/wav" "audio/flac")
                               "Content-Disposition" (str "attachment; filename=\"" (download-name name fmt) "\"")}
                     :body stream})))))
          (finally
            (delete-tree dir)
            (.release ^java.util.concurrent.Semaphore embed-lock)))))))

(defn routes
  [{:keys [ds] :as system}]
  (let [authed {:middleware [[wrap-account system]]}]
    [["/api"
      ["/auth/request" {:post {:parameters {:body [:map
                                                   [:email [:re #"^\s*[^@\s]+@[^@\s]+\.[^@\s]+\s*$"]]
                                                   [:device {:optional true} :boolean]]}
                               :handler (fn [r]
                                          (let [{:keys [email device]} (get-in r [:parameters :body])]
                                            (if-let [d (auth/request-login! system email (boolean device))]
                                              {:status 200 :body d}
                                              {:status 204})))}}]
      ["/auth/peek" {:post {:parameters {:body [:map [:token :string]]}
                            :handler (fn [r]
                                       (if-let [{:keys [device? code]} (auth/peek-login system (get-in r [:parameters :body :token]))]
                                         {:status 200 :body (cond-> {:device device?} code (assoc :code code))}
                                         {:status 401 :body {:error "invalid or expired link"}}))}}]
      ["/auth/poll" {:post {:parameters {:body [:map [:poll :string]]}
                            :handler (fn [r]
                                       (let [res (auth/poll-login! system (get-in r [:parameters :body :poll]))]
                                         (case res
                                           :pending {:status 202 :body {:status "pending"}}
                                           :expired {:status 410 :body {:error "sign-in expired, request a new link"}}
                                           {:status 200 :body res})))}}]
      ["/auth/verify" {:post {:parameters {:body [:map [:token :string]]}
                              :handler (fn [r]
                                         (if-let [s (auth/verify-login! system (get-in r [:parameters :body :token]))]
                                           {:status 200 :body {:session s}}
                                           {:status 401 :body {:error "invalid or expired link"}}))}}]
      ["/me/works" (merge authed {:get {:handler (fn [r] {:status 200 :body (works/list-own ds (:account-id r))})}})]
      ["/works" (merge authed {:post {:parameters {:body works/Record}
                                      :handler (fn [r]
                                                 {:status 201
                                                  :body (works/create! system (:account-id r)
                                                                       (get-in r [:parameters :body]))})}})]
      ["/works/:id"
       ["" {:parameters {:path [:map [:id WorkId]]}
            :get {:handler (fn [r] (if-let [w (works/public-view ds (work-id r))]
                                     {:status 200 :body w}
                                     (not-found)))}
            :put (merge authed
                        {:parameters {:body works/Record}
                         :handler (fn [r]
                                    (if-let [v (works/update-record! system (:account-id r) (work-id r)
                                                                     (get-in r [:parameters :body]))]
                                      {:status 200 :body {:version v}}
                                      (not-found)))})}]
       ["/finalize" (merge authed
                           {:parameters {:path [:map [:id WorkId]]}
                            :post {:parameters {:body [:map
                                                       [:fingerprint [:vector {:min 8 :max 100000} :int]]
                                                       [:audio-sha256 [:re #"^[0-9a-f]{64}$"]]]}
                                   :handler (fn [r]
                                              (if (works/finalize! system (:account-id r) (work-id r)
                                                                   (get-in r [:parameters :body]))
                                                {:status 204}
                                                (not-found)))}})]
       ["/embed" (merge authed
                        {:parameters {:path [:map [:id WorkId]]
                                      :query [:map
                                              [:format {:optional true} [:enum "flac" "wav"]]
                                              [:name {:optional true} [:string {:max 200}]]]}
                         :post {:handler (fn [r] (embed-upload system r))}})]
       ["/versions/:v/proof.ots" (download ds works/ots-proof "application/octet-stream" "proof.ots")]
       ["/versions/:v/proof.tsr" (download ds works/tsa-proof "application/timestamp-reply" "proof.tsr")]
       ["/versions/:v/record.json" (download ds works/record-bytes "application/json" "record.json")]]
      ["/lookup" {:post {:parameters {:body [:map
                                             [:candidates [:vector {:max 64} [:string {:max 64}]]]
                                             [:fingerprint {:optional true} [:vector {:max 100000} :int]]]}
                         :handler (fn [r]
                                    (let [{:keys [candidates fingerprint]} (get-in r [:parameters :body])]
                                      {:status 200 :body (works/lookup system candidates fingerprint)}))}}]
      ["/detect" {:post {:handler (fn [r] (detect-upload system r))}}]]]))

(defn- spa-fallback
  "Client-side routes (/, /works, /w/123, /login) get the app shell, with <base href> set to
  the mount prefix so relative asset and API paths resolve under it."
  [base-path]
  (fn [{:keys [request-method uri]}]
    (when (and (= :get request-method) (not (str/starts-with? uri "/api/")))
      (some-> (io/resource "public/index.html")
              slurp
              (str/replace "<base href=\"/\">" (str "<base href=\"" base-path "/\">"))
              (->> (hash-map :status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body))))))

(defn- wrap-base-path
  "Mounts the app under base-path (e.g. \"/earmark\"): strips it from :uri, 404s elsewhere."
  [handler base-path]
  (if (str/blank? base-path)
    handler
    (fn [{:keys [uri] :as request}]
      (cond
        (= uri base-path) {:status 301 :headers {"Location" (str base-path "/")}}
        (str/starts-with? uri (str base-path "/")) (handler (assoc request :uri (subs uri (count base-path))))
        :else {:status 404 :body "not found"}))))

(def desktop-origins
  "Origins of the Tauri app's webview (Linux/macOS, Windows)."
  #{"tauri://localhost" "http://tauri.localhost" "https://tauri.localhost"})

(defn- wrap-cors
  "Allows the desktop app's origins, plus extra-origins (dev: `cargo tauri dev` serves the UI from
  http://localhost:1430)."
  [handler extra-origins]
  (let [origins (into desktop-origins extra-origins)]
    (fn [request]
      (let [origin (get-in request [:headers "origin"])
            allowed? (contains? origins origin)]
        (if (and allowed? (= :options (:request-method request)))
          {:status 204
           :headers {"Access-Control-Allow-Origin" origin
                     "Access-Control-Allow-Methods" "GET, POST, PUT, OPTIONS"
                     "Access-Control-Allow-Headers" "Authorization, Content-Type"
                     "Access-Control-Max-Age" "600"
                     "Vary" "Origin"}}
          (cond-> (handler request)
            allowed? (update :headers assoc "Access-Control-Allow-Origin" origin "Vary" "Origin")))))))

(def ^:private exception-middleware
  "Like reitit's default, but logs unhandled errors (class + message only, no request data)."
  (exception/create-exception-middleware
   (merge exception/default-handlers
          {::exception/default (fn [^Exception e request]
                                 (println "ERROR" (:request-method request) (:uri request)
                                          (.getName (class e)) (ex-message e))
                                 {:status 500 :body {:error "internal error"}})})))

(defn- handler*
  [system]
  (ring/ring-handler
   (ring/router
    (routes system)
    {:data {:coercion (rcm/create {:compile mu/closed-schema :strip-extra-keys false})
            :muuntaja m/instance
            :middleware [parameters/parameters-middleware
                         muuntaja/format-middleware
                         exception-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}})
   (ring/routes
    (ring/create-resource-handler {:path "/" :root "public" :index-files []})
    (spa-fallback (:base-path system ""))
    (ring/create-default-handler))))

(defn handler
  [system]
  (wrap-cors (wrap-base-path (handler* system) (:base-path system "")) (:cors-origins system)))
