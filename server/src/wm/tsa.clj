(ns wm.tsa
  "RFC 3161 timestamps from a public timestamp authority (TSA): the TSA signs \"this SHA-256 existed
  at this time\". The stored response (.tsr) verifies offline with
  `openssl ts -verify -data record.json -in proof.tsr -CAfile <ca bundle>`."
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.security MessageDigest SecureRandom)
           (java.time Duration)
           (org.bouncycastle.tsp TSPAlgorithms TimeStampRequestGenerator TimeStampResponse)))

(def default-urls
  "Tried in order until one answers."
  ["http://timestamp.digicert.com" "https://freetsa.org/tsr"])

(def ^:private client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) (.build))))

(defn- post
  ^bytes [url ^bytes body]
  (let [resp (.send ^HttpClient @client
                    (-> (HttpRequest/newBuilder (URI. url))
                        (.timeout (Duration/ofSeconds 10))
                        (.header "Content-Type" "application/timestamp-query")
                        (.POST (HttpRequest$BodyPublishers/ofByteArray body))
                        (.build))
                    (HttpResponse$BodyHandlers/ofByteArray))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info "TSA HTTP error" {:url url :status (.statusCode resp)})))
    (.body resp)))

(defn stamp-at
  "One TSA. Returns {:response bytes :time java.util.Date :url url}; throws unless the response is
  granted, carries our nonce and the SHA-256 of data."
  [url ^bytes data]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") data)
        req (.generate (doto (TimeStampRequestGenerator.) (.setCertReq true))
                       TSPAlgorithms/SHA256 digest (BigInteger. 64 (SecureRandom.)))
        resp (TimeStampResponse. (post url (.getEncoded req)))]
    (.validate resp req)
    {:response (.getEncoded resp)
     :time (.getGenTime (.getTimeStampInfo (.getTimeStampToken resp)))
     :url url}))

(defn stamp
  "First TSA that succeeds, or throws with every failure."
  ([data] (stamp default-urls data))
  ([urls ^bytes data]
   (loop [[url & more] urls errors []]
     (if-not url
       (throw (ex-info "no TSA answered" {:errors errors}))
       (let [r (try (stamp-at url data)
                    (catch Exception e {:error (str url ": " (ex-message e))}))]
         (if (:error r) (recur more (conj errors (:error r))) r))))))

(defn tsa-name
  "Display name for a TSA url."
  [url]
  (condp re-find (str url)
    #"digicert" "DigiCert"
    #"freetsa" "FreeTSA"
    (.getHost (URI. url))))
