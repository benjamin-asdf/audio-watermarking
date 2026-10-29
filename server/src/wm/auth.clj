(ns wm.auth
  "Email magic-link login. Only SHA-256 hashes of tokens are stored."
  (:require [clojure.string :as str]
            [wm.db :as db])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(defn random-token
  []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn sha256
  ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn normalize-email
  [email]
  (str/lower-case (str/trim email)))

(def ^:private code-alphabet "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

(defn match-code
  "Short code shown both in the desktop app and on the confirm page, derived from the poll hash,
  so the artist can check that the browser confirms *their* app's request."
  [^bytes poll-hash]
  (apply str (for [i (range 4)] (nth code-alphabet (mod (aget poll-hash i) 32)))))

(defn request-login!
  "Creates the account on first use and sends a link. send-login is
  (fn [{:keys [email link code]}]). With device? the caller (the desktop app) also gets a secret
  poll id to claim the session with, and the code to show. Returns nil or {:poll :code}."
  ([system email] (request-login! system email false))
  ([{:keys [ds public-url send-login]} email device?]
   (let [email (normalize-email email)
         account (db/q1 ds ["INSERT INTO accounts (email) VALUES (?)
                             ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email
                             RETURNING id" email])
         token (random-token)
         poll (when device? (random-token))
         code (some-> poll sha256 match-code)]
     (db/q1 ds ["INSERT INTO login_tokens (token_hash, account_id, expires_at, poll_hash)
                 VALUES (?, ?, now() + interval '15 minutes', ?)"
                (sha256 token) (:id account) (some-> poll sha256)])
     (send-login (cond-> {:email email :link (str public-url "/login#" token)}
                   code (assoc :code code)))
     (when poll {:poll poll :code code}))))

(defn peek-login
  "What a still-valid link is for, without using it: {:device? bool :code str-or-nil}, or nil."
  [{:keys [ds]} token]
  (when-let [row (db/q1 ds ["SELECT poll_hash FROM login_tokens
                             WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()"
                            (sha256 token)])]
    {:device? (some? (:poll-hash row))
     :code (some-> (:poll-hash row) match-code)}))

(defn- new-session!
  [ds account-id]
  (let [session (random-token)]
    (db/q1 ds ["INSERT INTO sessions (token_hash, account_id) VALUES (?, ?)" (sha256 session) account-id])
    session))

(defn verify-login!
  "Consumes a magic-link token. Returns a session token, or nil."
  [{:keys [ds]} token]
  (when-let [row (db/q1 ds ["UPDATE login_tokens SET used_at = now()
                             WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()
                             RETURNING account_id" (sha256 token)])]
    (new-session! ds (:account-id row))))

(defn poll-login!
  "Desktop app: :pending until the link is confirmed, then {:session ...} exactly once.
  :expired when the poll id is unknown, already claimed, or the link was not used in time."
  [{:keys [ds]} poll]
  (let [h (sha256 poll)]
    (if-let [row (db/q1 ds ["UPDATE login_tokens SET poll_claimed_at = now()
                             WHERE poll_hash = ? AND used_at IS NOT NULL AND poll_claimed_at IS NULL
                               AND used_at > now() - interval '15 minutes'
                             RETURNING account_id" h])]
      {:session (new-session! ds (:account-id row))}
      (if (db/q1 ds ["SELECT 1 AS x FROM login_tokens
                      WHERE poll_hash = ? AND used_at IS NULL AND expires_at > now()" h])
        :pending
        :expired))))

(defn session-account
  "Account id for a bearer session token, or nil."
  [{:keys [ds]} token]
  (when token
    (:account-id (db/q1 ds ["SELECT account_id FROM sessions WHERE token_hash = ?" (sha256 token)]))))

(defn bearer-token
  [request]
  (some->> (get-in request [:headers "authorization"])
           (re-matches #"(?i)Bearer\s+(\S+)")
           second))
