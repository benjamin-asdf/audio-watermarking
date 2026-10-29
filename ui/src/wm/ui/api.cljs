(ns wm.ui.api
  "HTTP client for the server API. Session token lives in localStorage."
  (:require [clojure.string :as str]))

;; "" = same origin (website). The desktop build points this at the hosted server.
(goog-define API_BASE "")

(defn base-path
  "Mount prefix of the website from <base href>, e.g. \"/earmark\" (\"\" at the root)."
  []
  (str/replace (.-pathname (js/URL. js/document.baseURI)) #"/$" ""))

(defn- api-root
  []
  (if (seq API_BASE) API_BASE (base-path)))

(defn session
  []
  (try (js/localStorage.getItem "wm-session") (catch :default _ nil)))

(defn set-session!
  [s]
  (try (if s (js/localStorage.setItem "wm-session" s) (js/localStorage.removeItem "wm-session"))
       (catch :default _ nil)))

(defn- kebab-json
  [x]
  (js->clj x :keywordize-keys true))

(defn request
  "Returns a promise of {:status :body}. body is JSON (keywordized) or raw for binary."
  [method path & [{:keys [json body]}]]
  (let [headers (cond-> {}
                  json (assoc "Content-Type" "application/json")
                  (session) (assoc "Authorization" (str "Bearer " (session))))]
    (-> (js/fetch (str (api-root) path)
                  (clj->js {:method (str/upper-case (name method))
                            :headers headers
                            :body (cond json (js/JSON.stringify (clj->js json))
                                        body body)}))
        (.then (fn [resp]
                 (let [ct (or (.get (.-headers resp) "content-type") "")]
                   (if (str/includes? ct "json")
                     (.then (.json resp) (fn [j] {:status (.-status resp) :body (kebab-json j)}))
                     {:status (.-status resp) :body nil})))))))

(defn detect-file
  [file]
  (request :post "/api/detect" {:body file}))

(defn lookup
  [candidates fingerprint]
  (request :post "/api/lookup" {:json {:candidates candidates :fingerprint fingerprint}}))

(defn request-login
  "With device? (desktop app) the answer carries {:poll :code} to wait for the browser confirmation."
  [email & [device?]]
  (request :post "/api/auth/request" {:json (cond-> {:email email} device? (assoc :device true))}))
(defn peek-login [token] (request :post "/api/auth/peek" {:json {:token token}}))
(defn poll-login [poll] (request :post "/api/auth/poll" {:json {:poll poll}}))
(defn verify-login [token] (request :post "/api/auth/verify" {:json {:token token}}))
(defn my-works [] (request :get "/api/me/works"))
(defn work [id] (request :get (str "/api/works/" id)))
(defn create-work [record] (request :post "/api/works" {:json record}))
(defn update-work [id record] (request :put (str "/api/works/" id) {:json record}))

(defn finalize-work
  [id {:keys [fingerprint sha256]}]
  (request :post (str "/api/works/" id "/finalize") {:json {:fingerprint fingerprint :audio-sha256 sha256}}))

(defn embed-upload
  "Marks an audio file on the server (the work must still be pending). Promise of
  {:status 200 :blob :filename} or {:status :body} with the error."
  [id file]
  (let [lossless-wav? (re-find #"(?i)\.wav$" (.-name file))
        url (str (api-root) "/api/works/" id "/embed?format=" (if lossless-wav? "wav" "flac")
                 "&name=" (js/encodeURIComponent (.-name file)))]
    (-> (js/fetch url (clj->js {:method "POST" :headers {"Authorization" (str "Bearer " (session))} :body file}))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (let [cd (or (.get (.-headers resp) "content-disposition") "")
                         filename (or (second (re-find #"filename=\"([^\"]+)\"" cd)) "track.marked.flac")]
                     (.then (.blob resp) (fn [b] {:status 200 :blob b :filename filename})))
                   (-> (.json resp)
                       (.then (fn [j] {:status (.-status resp) :body (kebab-json j)})
                              (fn [_] {:status (.-status resp) :body nil})))))))))

(defn version-file-url
  "proof.tsr (signed timestamp), proof.ots (OpenTimestamps) or record.json (the bytes both cover)."
  [id version file]
  (str (api-root) "/api/works/" id "/versions/" version "/" file))
