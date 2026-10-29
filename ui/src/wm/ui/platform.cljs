(ns wm.ui.platform
  "Desktop (Tauri) vs website. Marking only exists on desktop: the audio never leaves the
  artist's machine. Reading works on both; desktop reads locally and sends only the candidates
  and fingerprint.")

(defn ^js tauri
  []
  (some-> js/window .-__TAURI__))

(defn desktop?
  []
  (some? (tauri)))

(defn- invoke
  [cmd args]
  (.invoke (.-core (tauri)) cmd (clj->js args)))

(defn- clj
  [x]
  (js->clj x :keywordize-keys true))

(def audio-extensions ["wav" "flac" "aif" "aiff" "mp3" "m4a" "aac" "ogg" "opus"])

(defn audio-path?
  [path]
  (let [ext (some-> (re-find #"\.([^./\\]+)$" path) second .toLowerCase)]
    (boolean (some #{ext} audio-extensions))))

(defn pick-audio-file
  "Promise of a path or nil."
  []
  (.open (.-dialog (tauri))
         (clj->js {:multiple false
                   :filters [{:name "Audio" :extensions audio-extensions}]})))

(defn listen-file-drops
  "Tauri delivers OS file drops on the window as webview events with real paths (HTML5 drop
  events carry no paths). Calls (on-event {:type :enter|:over|:drop|:leave :paths [...]}).
  Returns a promise of the unlisten function."
  [on-event]
  (.onDragDropEvent (.getCurrentWebview (.-webview (tauri)))
                    (fn [^js e]
                      (let [p (.-payload e)]
                        (on-event {:type (keyword (.-type p))
                                   :paths (vec (or (.-paths p) []))})))))

(defn marked-path
  "song.wav -> song.marked.wav; other inputs become .flac (marks are written lossless, WAV or FLAC)."
  [path]
  (let [[_ base ext] (re-matches #"(.*)\.([^./\\]+)$" path)
        ext (if (#{"wav" "flac"} (some-> ext .toLowerCase)) ext "flac")]
    (str (or base path) ".marked." ext)))

(defn embed
  "Promise of the wmark embed report."
  [input output payload]
  (.then (invoke "embed" {:input input :output output :payload payload}) clj))

(defn detect
  "Promise of {:candidates [...] :fingerprint [...]}."
  [input]
  (.then (invoke "detect" {:input input}) clj))

(defn reveal
  [path]
  (invoke "reveal" {:path path}))
