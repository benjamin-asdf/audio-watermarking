(ns wm.mail-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wm.mail :as mail])
  (:import (jakarta.mail Message$RecipientType)))

(def cfg {:host "mail.example.invalid" :port 587 :from "Earmark <earmark@example.invalid>"})

(defn- message
  [login]
  (mail/login-message (mail/session cfg) cfg login))

(deftest login-message
  (testing "web sign-in: link, no code"
    (let [m (message {:email "artist@example.invalid" :link "https://x.invalid/login#tok"})
          body (str (.getContent m))]
      (is (= "Your Earmark sign-in link" (.getSubject m)))
      (is (= "earmark@example.invalid" (.getAddress (first (.getFrom m)))))
      (is (= "artist@example.invalid" (str (first (.getRecipients m Message$RecipientType/TO)))))
      (is (str/includes? body "https://x.invalid/login#tok"))
      (is (not (str/includes? body "code")))))
  (testing "desktop sign-in: the match code is in the mail"
    (let [body (str (.getContent (message {:email "a@example.invalid" :link "L" :code "K7QZ"})))]
      (is (str/includes? body "    K7QZ")))))
