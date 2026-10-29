(ns wm.ui.core
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [wm.ui.api :as api]
            [wm.ui.platform :as platform]))

(def product "Earmark")

;; ---------------------------------------------------------------------------
;; routing

(defonce route (r/atom nil))

(defn parse-route
  "Path without the mount prefix (see api/base-path)."
  [path]
  (condp re-matches path
    #"/" [:read]
    #"/login" [:login]
    #"/works" [:works]
    #"/mark" [:mark]
    #"/w/(\d+)" :>> (fn [[_ id]] [:work id])
    [:not-found]))

(defn current-path
  []
  (let [p (subs js/location.pathname (count (api/base-path)))]
    (if (str/blank? p) "/" p)))

(defn navigate!
  [path]
  (.pushState js/history nil "" (str (api/base-path) path))
  (reset! route (parse-route path)))

(defn link
  [path & children]
  (into [:a {:href (str (api/base-path) path) :on-click (fn [e] (.preventDefault e) (navigate! path))}] children))

;; ---------------------------------------------------------------------------
;; shared pieces

(defn status-banner
  [status]
  (case (keyword status)
    :verified [:div.banner.ok [:strong "Verified."] " This recording carries a registered mark and matches the registered audio."]
    :audio-differs [:div.banner.warn [:strong "Mark found, but the audio differs."]
                    " The mark belongs to the work below, but this is not the registered recording: a remix, a sample, or a copied mark."]
    :id-found [:div.banner.ok [:strong "Mark found."]]
    :unknown-id [:div.banner.warn [:strong "Mark found, but its registration is not public."]]
    :no-watermark [:div.banner [:strong "No mark found."] " This file carries no " product " mark, or it was altered too much (heavy noise, a very short excerpt)."]
    [:div.banner.warn "Unexpected answer: " (str status)]))

(defn- utc-time
  [iso]
  (str (.toLocaleString (js/Date. iso) "en-GB" #js {:timeZone "UTC" :dateStyle "medium" :timeStyle "short"})
       " UTC"))

(defn proof-of-date
  "Signed timestamp (RFC 3161) first; OpenTimestamps as the second, long-term proof."
  [work-id {:keys [version tsa-time tsa-name ots-status]}]
  (let [file (fn [f label] [:a {:href (api/version-file-url work-id version f)} label])]
    [:<> [:dt "Proof of date"]
     [:dd
      (if tsa-time
        [:span "Signed " (utc-time tsa-time) " by " tsa-name "'s timestamp service · " (file "proof.tsr" "download proof")]
        [:span "Being signed, check back in a few minutes"])
      [:details.small
       [:summary "What is this?"]
       [:p "An independent timestamp service signed a fingerprint of this record. The signature shows "
        "that this exact record existed at that time, and anyone can check it without trusting "
        product ": download the " (file "record.json" "record") " and the proof, then run "
        [:code "openssl ts -verify -data record.json -in proof.tsr -CAfile <CA certificates>"] "."]
       (when (#{"pending" "complete"} ots-status)
         [:p "A second, independent proof comes from the public "
          [:a {:href "https://opentimestamps.org" :rel "noopener noreferrer" :target "_blank"} "OpenTimestamps"]
          " system and keeps working even if a timestamp service shuts down: "
          (file "proof.ots" "proof.ots")
          (when (= "pending" ots-status) " (ready within a few hours)") "."])]]]))

(defn record-card
  [{:keys [work-id record registered-at versions]}]
  (let [{:keys [artist title isrc links contact license]} record
        latest (last versions)]
    [:div.card
     [:div.title title]
     [:div.artist artist]
     [:dl
      (when isrc [:<> [:dt "ISRC"] [:dd isrc]])
      (when contact [:<> [:dt "Contact"] [:dd contact]])
      (when license [:<> [:dt "Licence"] [:dd license]])
      (when (seq links)
        [:<> [:dt "Links"] [:dd (for [l links] ^{:key l} [:div [:a {:href l :rel "noopener noreferrer" :target "_blank"} l]])]])
      [:dt "Registered"] [:dd (some-> registered-at (subs 0 10))]
      (when latest
        [proof-of-date work-id latest])]
     [:div.small [link (str "/w/" work-id) "Permalink"]]]))

(defn drop-zone
  [{:keys [label on-file busy?]}]
  (let [over? (r/atom false)]
    (fn [{:keys [label on-file busy? busy-label]}]
      [:label.drop {:class [(when @over? "over") (when busy? "busy")]
                    :on-drag-over (fn [e] (.preventDefault e) (reset! over? true))
                    :on-drag-leave #(reset! over? false)
                    :on-drop (fn [e]
                               (.preventDefault e)
                               (reset! over? false)
                               (when-let [f (aget (.. e -dataTransfer -files) 0)] (on-file f)))}
       [:input {:type "file" :accept "audio/*"
                :on-change (fn [e] (when-let [f (aget (.. e -target -files) 0)] (on-file f)))}]
       (if busy? (or busy-label "Listening…") label)])))

(defn path-drop-zone
  "Desktop: drop an audio file anywhere on the window, or click to pick one. Calls (on-path path)."
  [_]
  (r/with-let [over? (r/atom false)
               rejected (r/atom nil)
               props (r/atom nil)
               unlisten (platform/listen-file-drops
                         (fn [{:keys [type paths]}]
                           (case type
                             (:enter :over) (reset! over? true)
                             :leave (reset! over? false)
                             :drop (let [{:keys [on-path busy?]} @props
                                         path (first (filter platform/audio-path? paths))]
                                     (reset! over? false)
                                     (when-not busy?
                                       (if path
                                         (do (reset! rejected nil) (on-path path))
                                         (reset! rejected (first paths)))))
                             nil)))]
    (let [{:keys [label busy? on-path]} (reset! props (r/props (r/current-component)))]
      [:div
       [:button.drop {:type "button"
                      :class [(when @over? "over") (when busy? "busy")]
                      :disabled busy?
                      :on-click #(.then (platform/pick-audio-file) (fn [p] (when p (reset! rejected nil) (on-path p))))}
        (cond busy? "Listening…" @over? "Drop it" :else label)]
       (when-let [r @rejected]
         [:div.banner.warn "Not an audio file: " [:code (last (str/split r #"[/\\]"))]])])
    (finally (.then unlisten (fn [f] (f))))))

;; ---------------------------------------------------------------------------
;; pages

(defn read-page
  []
  (let [state (r/atom {})]
    (fn []
      (let [{:keys [busy result error]} @state
            show (fn [p] (-> p
                             (.then (fn [{:keys [status body]}]
                                      (if (= 200 status)
                                        (reset! state {:result body})
                                        (reset! state {:error (or (:error body) "Something went wrong.")}))))
                             (.catch (fn [e] (reset! state {:error (str e)})))))]
        [:main
         [:h1 "Who made this track?"]
         [:p.lead "Drop any audio file or clip. If the artist marked it with " product
          ", you'll see who it belongs to, even after MP3/AAC conversion or cutting."]
         (if (platform/desktop?)
           [path-drop-zone {:label "Drop an audio file here, or click to choose"
                            :busy? busy
                            :on-path (fn [path]
                                       (swap! state assoc :busy true)
                                       (show (.then (platform/detect path)
                                                    (fn [{:keys [candidates fingerprint]}]
                                                      (api/lookup (mapv :payload candidates) fingerprint)))))}]
           [drop-zone {:label "Drop an audio file here, or click to choose"
                       :busy? busy
                       :on-file (fn [f] (swap! state assoc :busy true) (show (api/detect-file f)))}])
         (when-not (platform/desktop?)
           [:p.small "The file is analysed and discarded; we don't keep it."])
         (when error [:div.banner.warn error])
         (when result
           [:<> [status-banner (:status result)]
            (when (:work result) [record-card (:work result)])])
         [:section.artists
          [:h2 "Artists: mark your music"]
          [:p product " hides an inaudible ID in your track, right on your computer: your audio is never uploaded. "
           "Attach who you are and where to find you. Anyone who finds the track can look it up here."]
          [link (if (api/session) "/mark" "/login") "Get started →"]]]))))

(defn- confirm-device-login
  "Browser, opened from the mail link of a desktop sign-in: confirm explicitly (mail scanners open
  links too) after comparing the code with the one the app shows."
  [token code state]
  (case (:step @state)
    :confirmed [:div.banner.ok [:strong "Done."] " Go back to the " product " app: it signs in by itself."]
    [:div
     [:p "The " product " desktop app asked to sign in to your account. Does the app show this code?"]
     [:p.code-big code]
     [:button.big {:on-click #(-> (api/verify-login token)
                                  (.then (fn [{:keys [status body]}]
                                           (if (= 200 status)
                                             (do (api/set-session! (:session body)) (swap! state assoc :step :confirmed))
                                             (swap! state assoc :error "This link is invalid or expired. Request a new one in the app.")))))}
      "Yes, sign in the app"]
     [:p.small "If you didn't just try to sign in, close this page. Nothing happens without this button."]]))

(defn- wait-for-browser
  "Desktop app: poll until the link was confirmed in the browser (15 min)."
  [{:keys [poll code]} state]
  (r/with-let [timer (js/setInterval
                      #(-> (api/poll-login poll)
                           (.then (fn [{:keys [status body]}]
                                    (case status
                                      200 (do (api/set-session! (:session body)) (navigate! "/works"))
                                      202 nil
                                      (swap! state assoc :device nil :sent false
                                             :error "The sign-in expired. Please request a new link.")))))
                      2000)]
    [:div
     [:p "We sent a sign-in link to " [:strong (:email @state)] ". Open it on any device and confirm."]
     [:p "The page will show this code:"]
     [:p.code-big code]
     [:p.small "This window signs in by itself once you confirm."]]
    (finally (js/clearInterval timer))))

(defn login-page
  []
  (let [state (r/atom {:email ""})
        token (some-> js/location.hash (subs 1) not-empty)]
    (when token
      (-> (api/peek-login token)
          (.then (fn [{:keys [status body]}]
                   (cond
                     (not= 200 status) (swap! state assoc :error "This link is invalid or expired. If you requested several links, use the newest mail; otherwise request a new one.")
                     (:device body) (swap! state assoc :confirm-code (:code body))
                     :else (.then (api/verify-login token)
                                  (fn [{:keys [status body]}]
                                    (if (= 200 status)
                                      (do (api/set-session! (:session body)) (navigate! "/works"))
                                      (swap! state assoc :error "This link is invalid or expired. Request a new one.")))))))))
    (fn []
      (let [{:keys [email sent error device confirm-code sending]} @state]
        [:main
         [:h1 "Sign in"]
         (cond
           confirm-code [confirm-device-login token confirm-code state]
           (and token (not error)) [:p "Signing you in…"]
           device [wait-for-browser device state]
           sent [:p "Check your inbox: we sent a sign-in link to " [:strong email] "."]
           (not token)
           [:form {:on-submit (fn [e]
                                (.preventDefault e)
                                ;; Sending the mail takes a second or two; a second submit would start a
                                ;; second sign-in and the app would wait for the wrong link.
                                (when-not (:sending @state)
                                  (swap! state assoc :sending true :error nil)
                                  (-> (api/request-login email (platform/desktop?))
                                      (.then (fn [{:keys [status body]}]
                                               (case status
                                                 200 (swap! state assoc :device body :sending false)
                                                 204 (swap! state assoc :sent true :sending false)
                                                 (swap! state assoc :sending false :error "Please enter a valid email address."))))
                                      (.catch (fn [e] (swap! state assoc :sending false :error (str "Could not reach the server: " e)))))))}
            [:label "Email" [:input {:type "email" :required true :default-value email
                                     :on-change #(swap! state assoc :email (.. % -target -value))}]]
            [:button {:disabled sending} (if sending "Sending…" "Send sign-in link")]
            [:p.small "No password. We email you a link."]])
         (when error [:div.banner.warn error])]))))

(def empty-record {:artist "" :title "" :isrc "" :links "" :contact "" :license ""})

(defn clean-record
  "Form strings -> API record (drops blanks, splits links by line)."
  [form]
  (let [links (->> (str/split-lines (:links form)) (map str/trim) (remove str/blank?) vec)]
    (cond-> (into {} (for [k [:artist :title :isrc :contact :license]
                           :let [v (str/trim (get form k ""))]
                           :when (seq v)]
                       [k v]))
      (seq links) (assoc :links links))))

(defn record-form
  [form]
  ;; Uncontrolled (:default-value): with React 18's async root, writing :value back on every key
  ;; let WebKitGTK (the Linux desktop app) drop or reorder typed characters. The form is
  ;; remounted whenever its data is reset, so the browser's own value never goes stale.
  (let [field (fn [k label & [{:keys [required placeholder textarea]}]]
                [:label label
                 [(if textarea :textarea :input)
                  {:default-value (get @form k) :required required :placeholder placeholder
                   :on-change #(swap! form assoc k (.. % -target -value))}]])]
    [:<>
     (field :artist "Artist name" {:required true})
     (field :title "Track title" {:required true})
     (field :isrc "ISRC (optional)" {:placeholder "e.g. DEA123456789"})
     (field :links "Links, one per line (optional)" {:textarea true :placeholder "https://…"})
     (field :contact "Contact (optional, public)" {:placeholder "booking@…"})
     (field :license "Licence / usage note (optional)" {:textarea true})]))

(defn error-text
  [{:keys [status body]}]
  (cond (= 401 status) "Please sign in again."
        (= 400 status) "Please check the fields (ISRC like DEA123456789, links starting with https://)."
        :else (or (:error body) (str "Server error " status))))

(def source-url "https://github.com/benjamin-asdf/audio-watermarking")
;; Installers for Linux, macOS and Windows, built by .github/workflows/desktop-release.yml.
(def desktop-app-url (str source-url "/releases/latest"))

(defn- save-blob!
  "Hands a downloaded file to the browser's save dialog."
  [blob filename]
  (let [url (js/URL.createObjectURL blob)
        a (doto (js/document.createElement "a") (aset "href" url) (aset "download" filename))]
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 60000)))

(defn web-mark
  "Website marking: upload, the server marks and registers, the marked copy comes back."
  []
  (let [form (r/atom empty-record)
        state (r/atom {:step :form})]
    (fn []
      (let [{:keys [step file error work-id result]} @state
            fail! (fn [e] (swap! state assoc :step :form :error (if (map? e) (error-text e) (str e))))]
        [:div
         [:p "Upload your track. We add the mark, register it and send the marked copy back."]
         [:p.small "We don't keep your file: it is deleted as soon as the marked copy is sent back. The code is "
          [:a {:href source-url :rel "noopener"} "open source"] ", so you can check what happens to it. "
          "Prefer to keep your audio on your computer? Mark offline with the "
          [:a {:href desktop-app-url :rel "noopener"} "desktop app"] " for Linux, macOS and Windows."]
         (if (= step :done)
           [:div
            [:div.banner.ok [:strong "Done."] " Your marked file " [:code (:filename result)] " is downloading."]
            [:p "Release " [:em "this"] " file. Keep the original as your master."]
            [:button {:on-click #(save-blob! (:blob result) (:filename result))} "Download again"] " "
            [link (str "/w/" work-id) "View public page"] " · "
            [:a {:href "#" :on-click (fn [e] (.preventDefault e) (reset! form empty-record) (reset! state {:step :form}))} "Mark another"]]
           [:form {:on-submit
                   (fn [e]
                     (.preventDefault e)
                     (swap! state assoc :step :working :error nil)
                     (-> (api/create-work (clean-record @form))
                         (.then (fn [{:keys [status body] :as resp}]
                                  (if (= 201 status)
                                    (do (swap! state assoc :work-id (:work-id body))
                                        (.then (api/embed-upload (:work-id body) file)
                                               (fn [{:keys [status] :as res}]
                                                 (if (= 200 status)
                                                   (do (save-blob! (:blob res) (:filename res))
                                                       (swap! state assoc :step :done :result res))
                                                   (fail! res)))))
                                    (fail! resp))))
                         (.catch fail!)))}
            [:label "Audio file (WAV, FLAC or AIFF is best, up to 150 MB)"
             [drop-zone {:label (if file
                                  [:span [:code.path (.-name file)] [:br] [:span.small "Drop or click to change"]]
                                  "Drop your track here, or click to choose")
                         :busy? (= step :working)
                         :busy-label "Uploading and marking… this takes a moment"
                         :on-file #(swap! state assoc :file %)}]]
            (when (and file (not (re-find #"(?i)\.(wav|flac|aiff?)$" (.-name file))))
              [:div.banner.warn "This is a compressed file. It works, but marking your lossless master gives the best quality."])
            [record-form form]
            [:button.big {:disabled (or (nil? file) (= step :working))}
             (if (= step :working) "Marking…" "Mark and register")]])
         (when error [:div.banner.warn error])]))))

(defn mark-page
  []
  (let [form (r/atom empty-record)
        state (r/atom {:step :form})]
    (fn []
      (let [{:keys [step path error report work-id]} @state
            fail! (fn [e] (swap! state assoc :step :form :error (if (map? e) (error-text e) (str e))))]
        [:main
         [:h1 "Mark a track"]
         (cond
           (not (api/session)) [:p "Please " [link "/login" "sign in"] " first."]
           (not (platform/desktop?)) [web-mark]
           (= step :done)
           [:div
            [:div.banner.ok [:strong "Done."] " Your marked file: " [:code (:output report)]]
            [:p "Release " [:em "this"] " file. Keep the original as your master."]
            [:button {:on-click #(platform/reveal (:output report))} "Show in folder"] " "
            [link (str "/w/" work-id) "View public page"] " · "
            [:a {:href "#" :on-click (fn [e] (.preventDefault e) (reset! form empty-record) (reset! state {:step :form}))} "Mark another"]]
           :else
           [:form {:on-submit
                   (fn [e]
                     (.preventDefault e)
                     (swap! state assoc :step :working :error nil)
                     (-> (api/create-work (clean-record @form))
                         (.then (fn [{:keys [status body] :as resp}]
                                  (if (= 201 status)
                                    (let [{:keys [work-id payload-hex]} body]
                                      (swap! state assoc :work-id work-id)
                                      (.then (platform/embed path (platform/marked-path path) payload-hex)
                                             (fn [report]
                                               (if-not (:verified report)
                                                 (throw (js/Error. "The mark could not be verified in the output file."))
                                                 (.then (api/finalize-work work-id report)
                                                        (fn [{:keys [status] :as resp}]
                                                          (if (= 204 status)
                                                            (swap! state assoc :step :done :report report)
                                                            (fail! resp))))))))
                                    (fail! resp))))
                         (.catch fail!)))}
            [:label "Audio file (WAV, FLAC or AIFF is best)"
             [path-drop-zone {:label (if path
                                       [:span [:code.path path] [:br] [:span.small "Drop or click to change"]]
                                       "Drop your track here, or click to choose")
                              :busy? (= step :working)
                              :on-path #(swap! state assoc :path %)}]]
            (when (and path (not (re-find #"(?i)\.(wav|flac|aiff?)$" path)))
              [:div.banner.warn "This is a compressed file. It works, but marking your lossless master gives the best quality."])
            [record-form form]
            [:button.big {:disabled (or (nil? path) (= step :working))}
             (if (= step :working) "Marking… (a few seconds per minute of audio)" "Mark and register")]])
         (when error [:div.banner.warn error])]))))

(defn works-page
  []
  (let [state (r/atom {:loading true})]
    (-> (api/my-works)
        (.then (fn [{:keys [status body]}]
                 (if (= 200 status)
                   (reset! state {:works body})
                   (do (api/set-session! nil) (navigate! "/login"))))))
    (fn []
      (let [{:keys [loading works]} @state]
        [:main
         [:div.row.between [:h1 "Your works"] [link "/mark" "Mark a track"]]
         (cond loading [:p "Loading…"]
               (empty? works) [:p "Nothing registered yet."]
               :else
               [:ul.works
                (for [{:keys [id status record created-at]} works]
                  ^{:key id}
                  [:li [link (str "/w/" id) [:strong (:title record)] " · " (:artist record)]
                   [:span.small " " (subs created-at 0 10) (when (= "pending" status) " · not finished (marking was interrupted)")]])])
         [:p.small [:a {:href "#" :on-click (fn [e] (.preventDefault e) (api/set-session! nil) (navigate! "/"))} "Sign out"]]]))))

(defn work-page
  [id]
  (let [state (r/atom {:loading true})
        load! (fn []
                (-> (js/Promise.all #js [(api/work id) (if (api/session) (api/my-works) (js/Promise.resolve nil))])
                    (.then (fn [[w mine]]
                             (reset! state {:work (when (= 200 (:status w)) (:body w))
                                            :mine? (boolean (some #(= id (:id %)) (:body mine)))})))))]
    (load!)
    (fn [id]
      (let [{:keys [loading work mine? editing error]} @state]
        [:main
         (cond
           loading [:p "Loading…"]
           (nil? work) [:p "No public work with this ID."]
           editing
           (let [form (:form @state)]
             [:form {:on-submit (fn [e]
                                  (.preventDefault e)
                                  (-> (api/update-work id (clean-record @form))
                                      (.then (fn [{:keys [status] :as resp}]
                                               (if (= 200 status) (load!) (swap! state assoc :error (error-text resp)))))))}
              [:h1 "Edit public record"]
              [:p.small "Edits create a new timestamped version; earlier versions stay on record."]
              [record-form form]
              [:button "Save"] " "
              [:a {:href "#" :on-click (fn [e] (.preventDefault e) (swap! state dissoc :editing))} "Cancel"]])
           :else
           [:<> [record-card work]
            (when mine?
              [:button {:on-click #(swap! state assoc :editing true
                                          :form (r/atom (merge empty-record
                                                               (update (:record work) :links (fn [l] (str/join "\n" l))))))}
               "Edit record"])])
         (when error [:div.banner.warn error])]))))

(defn shell
  []
  (let [[page arg] @route]
    [:<>
     [:header
      [link "/" [:span.logo product]]
      [:nav
       [link "/" "Look up"]
       (if (api/session) [link "/works" "Your works"] [link "/login" "Sign in"])]]
     (case page
       :read [read-page]
       :login [login-page]
       :works [works-page]
       :mark [mark-page]
       :work ^{:key arg} [work-page arg]
       [:main [:h1 "Not found"] [link "/" "Home"]])
     ;; AGPL section 13: users of the hosted service get a link to its source.
     [:footer.small product " · "
      [:a {:href source-url :rel "noopener"} "open source (AGPL)"] " · "
      [:a {:href "https://benjamin-schwerdtner.de/" :rel "noopener"} "benjamin-schwerdtner.de"]]]))

(defonce root (atom nil))

(defn ^:dev/after-load render
  []
  (rdc/render @root [shell]))

(defn init
  []
  (reset! route (parse-route (current-path)))
  (.addEventListener js/window "popstate" #(reset! route (parse-route (current-path))))
  (reset! root (rdc/create-root (js/document.getElementById "app")))
  (render))
