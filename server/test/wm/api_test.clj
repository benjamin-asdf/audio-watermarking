(ns wm.api-test
  "End-to-end through the ring handler against the wm_test database."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [wm.api :as api]
            [wm.db :as db]
            [wm.works :as works]))

(def ds (db/datasource (or (System/getenv "WM_TEST_DB_URL")
                           "jdbc:postgresql://localhost:5439/wm_test?user=wm")))

(def sent (atom []))

(def system
  {:ds ds
   :secret (.getBytes "test-secret" "UTF-8")
   :engine :audiowmark
   :public-url "http://test"
   :send-login #(swap! sent conj %)
   :stamp (fn [^bytes b] (.getBytes (str "OTS:" (count b)) "UTF-8"))
   :stamp-async (fn [f] (f))
   :tsa-stamp (fn [^bytes b] {:response (.getBytes (str "TSR:" (count b)) "UTF-8")
                              :time (java.util.Date. 1793300000000)
                              :url "http://timestamp.digicert.com"})})

(defn reset-db
  [t]
  (jdbc/execute! ds ["DROP SCHEMA public CASCADE; CREATE SCHEMA public"])
  (db/migrate! ds)
  (reset! sent [])
  (t))

(use-fixtures :each reset-db)

(defn call
  ([method uri] (call method uri nil nil))
  ([method uri body] (call method uri body nil))
  ([method uri body session]
   (let [resp ((api/handler system)
               (cond-> {:request-method method :uri uri :headers {}}
                 body (-> (assoc :body (java.io.ByteArrayInputStream. (json/write-value-as-bytes body)))
                          (assoc-in [:headers "content-type"] "application/json"))
                 session (assoc-in [:headers "authorization"] (str "Bearer " session))))
         b (:body resp)]
     (assoc resp :json (when (and (instance? java.io.InputStream b)
                                  (re-find #"json" (str (get-in resp [:headers "Content-Type"]))))
                         (let [s (slurp b)]
                           (when (seq s) (json/read-value s json/keyword-keys-object-mapper))))))))

(defn login! [email]
  (is (= 204 (:status (call :post "/api/auth/request" {:email email}))))
  (let [token (second (re-find #"#(.+)$" (:link (last @sent))))]
    (get-in (call :post "/api/auth/verify" {:token token}) [:json :session])))

(def record {:artist "Test Artist" :title "Song" :links ["https://example.org"]})
;; Includes values above 2^31 like real fpcalc output.
(def fingerprint (vec (concat (range 100 300) (range 4294967000 4294967100))))

(deftest full-flow
  (let [session (login! "Artist@Example.org ")
        created (call :post "/api/works" record session)
        {:keys [work-id payload-hex]} (:json created)]
    (is (= 201 (:status created)))
    (is (= 32 (count payload-hex)))
    (testing "pending works are not public"
      (is (= 404 (:status (call :get (str "/api/works/" work-id)))))
      (is (= "unknown-id" (get-in (call :post "/api/lookup" {:candidates [payload-hex]}) [:json :status]))))
    (is (= 204 (:status (call :post (str "/api/works/" work-id "/finalize")
                              {:fingerprint fingerprint :audio-sha256 (apply str (repeat 64 "a"))}
                              session))))
    (testing "public view with stamped first version"
      (let [{:keys [json]} (call :get (str "/api/works/" work-id))]
        (is (= record (:record json)))
        (is (= ["pending"] (map :ots-status (:versions json))))
        (testing "signed timestamp right at finalize"
          (is (= ["DigiCert"] (map :tsa-name (:versions json))))
          (is (= ["2026-10-29T18:53:20Z"] (map :tsa-time (:versions json)))))))
    (testing "lookup statuses"
      (is (= "verified" (get-in (call :post "/api/lookup" {:candidates ["ffff" payload-hex] :fingerprint (subvec fingerprint 50 120)})
                                [:json :status])))
      (is (= "audio-differs" (get-in (call :post "/api/lookup" {:candidates ["ffff" payload-hex] :fingerprint (vec (repeat 70 0x55555555))})
                                     [:json :status])))
      (is (= "id-found" (get-in (call :post "/api/lookup" {:candidates [payload-hex]}) [:json :status])))
      (is (= "no-watermark" (get-in (call :post "/api/lookup" {:candidates [(apply str (repeat 32 "0")) "xyz"]})
                                    [:json :status]))))
    (testing "edit creates a new version; other accounts cannot edit"
      (is (= 2 (get-in (call :put (str "/api/works/" work-id) (assoc record :title "Song (v2)") session)
                       [:json :version])))
      (is (= 404 (:status (call :put (str "/api/works/" work-id) record (login! "other@example.org")))))
      (is (= "Song (v2)" (get-in (call :get (str "/api/works/" work-id)) [:json :record :title]))))
    (testing "proof downloads"
      (is (= 200 (:status (call :get (str "/api/works/" work-id "/versions/1/proof.ots")))))
      (is (= 200 (:status (call :get (str "/api/works/" work-id "/versions/1/proof.tsr")))))
      (is (= 200 (:status (call :get (str "/api/works/" work-id "/versions/2/proof.tsr")))))
      (is (= 200 (:status (call :get (str "/api/works/" work-id "/versions/1/record.json")))))
      (is (= 404 (:status (call :get (str "/api/works/" work-id "/versions/9/record.json"))))))
    (testing "own works list"
      (is (= [work-id] (map :id (:json (call :get "/api/me/works" nil session))))))))

(deftest auth-required-and-validation
  (is (= 401 (:status (call :post "/api/works" record))))
  (is (= 401 (:status (call :post "/api/works" record "bogus"))))
  (let [s (login! "a@b.cd")]
    (is (= 400 (:status (call :post "/api/works" (assoc record :evil 1) s))))
    (is (= 400 (:status (call :post "/api/works" (assoc record :links ["javascript:alert(1)"]) s))))))

(deftest magic-link-single-use
  (call :post "/api/auth/request" {:email "x@y.zz"})
  (let [token (second (re-find #"#(.+)$" (:link (last @sent))))]
    (is (string? (get-in (call :post "/api/auth/verify" {:token token}) [:json :session])))
    (is (= 401 (:status (call :post "/api/auth/verify" {:token token}))))))

(deftest cors-for-desktop-only
  (let [h (api/handler system)
        pre (fn [origin] (h {:request-method :options :uri "/api/works" :headers {"origin" origin}}))]
    (is (= "tauri://localhost" (get-in (pre "tauri://localhost") [:headers "Access-Control-Allow-Origin"])))
    (is (nil? (get-in (pre "https://evil.example") [:headers "Access-Control-Allow-Origin"])))))

(deftest mounted-under-base-path
  (let [h (api/handler (assoc system :base-path "/earmark"))
        get* (fn [uri] (h {:request-method :get :uri uri :headers {}}))]
    (testing "app shell carries the prefix in <base href>, also on client routes"
      (is (re-find #"<base href=\"/earmark/\">" (:body (get* "/earmark/"))))
      (is (re-find #"<base href=\"/earmark/\">" (:body (get* "/earmark/works")))))
    (is (= 301 (:status (get* "/earmark"))))
    (is (= 200 (:status (get* "/earmark/style.css"))))
    (is (= 404 (:status (get* "/api/works/1"))) "unprefixed paths are not served")
    (is (= 404 (:status (get* "/earmark/api/works/999"))) "API routes resolve under the prefix")))

(deftest cors-extra-origins
  (let [pre (fn [sys origin]
              ((api/handler sys) {:request-method :options :uri "/api/works" :headers {"origin" origin}}))]
    (is (nil? (get-in (pre system "http://localhost:1430") [:headers "Access-Control-Allow-Origin"])))
    (is (= "http://localhost:1430"
           (get-in (pre (assoc system :cors-origins ["http://localhost:1430"]) "http://localhost:1430")
                   [:headers "Access-Control-Allow-Origin"])))))

(deftest desktop-sign-in
  (let [{:keys [poll code]} (:json (call :post "/api/auth/request" {:email "app@x.zz" :device true}))
        token (second (re-find #"#(.+)$" (:link (last @sent))))]
    (is (re-matches #"[A-Z2-9]{4}" code))
    (is (= code (:code (last @sent))) "the mail shows the same code as the app")
    (is (= 202 (:status (call :post "/api/auth/poll" {:poll poll}))) "pending before the link is confirmed")
    (testing "peek tells the confirm page it is a desktop login, without using the link"
      (is (= {:device true :code code} (:json (call :post "/api/auth/peek" {:token token}))))
      (is (= {:device true :code code} (:json (call :post "/api/auth/peek" {:token token})))))
    (is (string? (get-in (call :post "/api/auth/verify" {:token token}) [:json :session])))
    (let [session (get-in (call :post "/api/auth/poll" {:poll poll}) [:json :session])]
      (is (string? session))
      (is (= 200 (:status (call :get "/api/me/works" nil session))) "the app's session works"))
    (is (= 410 (:status (call :post "/api/auth/poll" {:poll poll}))) "claimed only once")
    (is (= 410 (:status (call :post "/api/auth/poll" {:poll "unknown"}))))
    (is (= 401 (:status (call :post "/api/auth/peek" {:token token}))) "used link")))

(deftest web-sign-in-unchanged
  (is (= 204 (:status (call :post "/api/auth/request" {:email "web@x.zz"}))))
  (let [token (second (re-find #"#(.+)$" (:link (last @sent))))]
    (is (= {:device false} (:json (call :post "/api/auth/peek" {:token token}))))))

(deftest tsa-down-at-finalize
  (testing "finalize still succeeds; the backfill job stamps later"
    (let [down (assoc system :tsa-stamp (fn [_] (throw (ex-info "no TSA answered" {:errors ["x: down"]}))))
          session (login! "artist@example.org")
          {:keys [work-id]} (:json (call :post "/api/works" record session))
          handler (api/handler down)
          r (handler {:request-method :post :uri (str "/api/works/" work-id "/finalize")
                      :headers {"content-type" "application/json" "authorization" (str "Bearer " session)}
                      :body (java.io.ByteArrayInputStream.
                             (json/write-value-as-bytes {:fingerprint fingerprint
                                                         :audio-sha256 (apply str (repeat 64 "a"))}))})]
      (is (= 204 (:status r)))
      (is (= [nil] (map :tsa-name (get-in (call :get (str "/api/works/" work-id)) [:json :versions]))))
      (works/stamp-missing-tsa! system)
      (is (= ["DigiCert"] (map :tsa-name (get-in (call :get (str "/api/works/" work-id)) [:json :versions])))))))
