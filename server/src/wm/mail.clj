(ns wm.mail
  "Login mail over SMTP submission (STARTTLS required). Production: earmark@benjamin-schwerdtner.de on
  mail.benjamin-schwerdtner.de (~/repos/ftlm-mail)."
  (:require [clojure.string :as str])
  (:import (jakarta.mail Message$RecipientType Session Transport)
           (jakarta.mail.internet InternetAddress MimeMessage)
           (java.util Date Properties)))

(defn session
  [{:keys [host port from]}]
  (Session/getInstance
   (doto (Properties.)
     (.put "mail.smtp.host" host)
     (.put "mail.smtp.port" (str port))
     (.put "mail.smtp.auth" "true")
     (.put "mail.smtp.starttls.enable" "true")
     (.put "mail.smtp.starttls.required" "true")
     (.put "mail.smtp.ssl.checkserveridentity" "true")
     ;; Message-ID domain comes from this address, not from the box's host name.
     (.put "mail.from" (.getAddress (InternetAddress. from)))
     (.put "mail.smtp.connectiontimeout" "10000")
     (.put "mail.smtp.timeout" "20000")
     (.put "mail.smtp.writetimeout" "20000"))))

(defn login-body
  [{:keys [link code]}]
  (str/join
   "\n"
   (concat
    ["Sign in to Earmark:" "" link ""]
    (when code
      ["This link signs in your desktop app. Before you confirm, check that the app shows the code"
       ""
       (str "    " code)
       ""])
    ["The link works once and expires in 15 minutes."
     "If you didn't ask to sign in, ignore this mail."
     ""])))

(defn login-message
  ^MimeMessage [session {:keys [from]} {:keys [email] :as login}]
  (doto (MimeMessage. ^Session session)
    (.setFrom (InternetAddress. from))
    (.setRecipient Message$RecipientType/TO (InternetAddress. email))
    (.setSubject "Your Earmark sign-in link" "UTF-8")
    (.setText (login-body login) "UTF-8")
    (.setSentDate (Date.))))

(defn send-login
  "Sends the magic link. Throws on SMTP failure, so the request fails visibly."
  [{:keys [user password] :as cfg} login]
  (Transport/send (login-message (session cfg) cfg login) user password))
