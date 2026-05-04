(ns loom-exporter.loom-media
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom-exporter.archive :as archive]
            [loom-exporter.json :as json]
            [loom-exporter.loom-web :as loom-web]
            [loom-exporter.process :as process]
            [loom-exporter.util :as util]
            [loom-exporter.video :as video]))

(def get-video-ssr-query
  "query GetVideoSSR($videoId: ID!, $password: String) {
  getVideo(id: $videoId, password: $password) {
    __typename
    ... on PrivateVideo {
      id
      status
      message
      __typename
    }
    ... on VideoPasswordMissingOrIncorrect {
      id
      message
      __typename
    }
    ... on RegularUserVideo {
      id
      __typename
      createdAt
      description
      download_enabled
      folder_id
      is_protected
      needs_password
      owner {
        display_name
        id
        __typename
      }
      privacy
      s3_id
      name
      video_properties {
        duration
        durationMs
        height
        width
        microphone_enabled
        recording_type
        __typename
      }
      playable_duration
      source_duration
      visibility
    }
  }
}")

(def get-video-source-query
  "query GetVideoSource($videoId: ID!, $password: String, $acceptableMimes: [CloudfrontVideoAcceptableMime]) {
  getVideo(id: $videoId, password: $password) {
    ... on RegularUserVideo {
      id
      nullableRawCdnUrl(acceptableMimes: $acceptableMimes, password: $password) {
        url
        credentials {
          Policy
          Signature
          KeyPairId
          __typename
        }
        __typename
      }
      __typename
    }
    __typename
  }
}")

(def fetch-video-transcript-query
  "query FetchVideoTranscript($videoId: ID!, $password: String) {
  fetchVideoTranscript(videoId: $videoId, password: $password) {
    ... on VideoTranscriptDetails {
      id
      video_id
      source_url
      captions_source_url
      __typename
    }
    ... on GenericError {
      message
      __typename
    }
    __typename
  }
}")

(def fetch-chapters-query
  "query FetchChapters($videoId: ID!, $password: String) {
  fetchVideoChapters(videoId: $videoId, password: $password) {
    ... on VideoChapters {
      video_id
      content
      __typename
    }
    ... on EmptyChaptersPayload {
      content
      __typename
    }
    ... on InvalidRequestWarning {
      message
      __typename
    }
    ... on Error {
      message
      __typename
    }
    __typename
  }
}")

(defn- video-id [video-or-url]
  (or (:id video-or-url)
      (util/loom-id-from-url (:url video-or-url))
      (util/loom-id-from-url (str video-or-url))))

(defn- password [opts]
  (:video-password opts))

(defn- variables [opts id]
  {:videoId id
   :password (password opts)})

(defn- graphql-soft [opts operation variables query]
  (try
    (loom-web/graphql! opts operation variables query)
    (catch Exception e
      {:error {:message (.getMessage e)
               :data (ex-data e)}})))

(defn session-url! [opts endpoint id]
  (let [cookie (loom-web/cookie-header opts)
        body (json/write-str {:anonID (str (java.util.UUID/randomUUID))
                              :deviceID nil
                              :force_original false
                              :password (password opts)})
        request-builder (-> (java.net.http.HttpRequest/newBuilder
                             (java.net.URI/create
                              (str "https://www.loom.com/api/campaigns/sessions/" id "/" endpoint)))
                            (.header "accept" "application/json")
                            (.header "content-type" "application/json")
                            (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body)))
        request-builder (cond-> request-builder
                          (not (str/blank? cookie)) (.header "cookie" cookie))
        response (.send (java.net.http.HttpClient/newHttpClient)
                        (.build request-builder)
                        (java.net.http.HttpResponse$BodyHandlers/ofString))
        parsed (try
                 (json/read-json-string (.body response))
                 (catch Exception _ nil))]
    (when (<= 200 (.statusCode response) 299)
      (:url parsed))))

(defn- signed-cdn-url [source-payload]
  (get-in source-payload [:data :getVideo :nullableRawCdnUrl :url]))

(defn- ext-from-url [url]
  (when-let [path (some-> url java.net.URI/create .getPath)]
    (some->> path
             (re-find #"\.([A-Za-z0-9]+)$")
             second
             str/lower-case)))

(defn- uri [s]
  (java.net.URI/create s))

(defn- url-query [url]
  (.getQuery (uri url)))

(defn- resolve-url [base ref]
  (str (.resolve (uri base) ref)))

(defn- append-query [url query]
  (if (or (str/blank? query)
          (str/includes? url "?"))
    url
    (str url "?" query)))

(defn- read-url-string [url]
  (slurp url))

(defn- rewrite-map-uri [line base-url query]
  (str/replace line
               #"URI=\"([^\"]+)\""
               (fn [[_ ref]]
                 (str "URI=\"" (append-query (resolve-url base-url ref) query) "\""))))

(defn- rewrite-master-media-uri [line base-url query localize!]
  (str/replace line
               #"URI=\"([^\"]+)\""
               (fn [[_ ref]]
                 (str "URI=\"" (localize! (resolve-url base-url ref) query) "\""))))

(defn- rewrite-media-playlist [playlist-url query out-file]
  (let [content (read-url-string (append-query playlist-url query))
        rewritten (->> (str/split-lines content)
                       (map (fn [line]
                              (cond
                                (str/starts-with? line "#EXT-X-MAP:")
                                (rewrite-map-uri line playlist-url query)

                                (or (str/blank? line) (str/starts-with? line "#"))
                                line

                                :else
                                (append-query (resolve-url playlist-url line) query))))
                       (str/join "\n"))]
    (spit out-file (str rewritten "\n"))))

(defn- local-hls-playlist! [url dir]
  (let [query (url-query url)
        master (read-url-string url)
        hls-dir (io/file dir ".loom-hls")
        _ (.mkdirs hls-dir)
        counter (atom 0)
        localize! (fn [playlist-url query]
                    (let [local-name (format "media-%03d.m3u8" (swap! counter inc))
                          local-file (io/file hls-dir local-name)]
                      (rewrite-media-playlist playlist-url query local-file)
                      local-name))
        rewritten-lines
        (->> (str/split-lines master)
             (map (fn [line]
                    (cond
                      (str/starts-with? line "#EXT-X-MEDIA:")
                      (rewrite-master-media-uri line url query localize!)

                      (or (str/blank? line) (str/starts-with? line "#"))
                      line

                      :else
                      (localize! (resolve-url url line) query))))
             (str/join "\n"))
        master-file (io/file hls-dir "master.m3u8")]
    (spit master-file (str rewritten-lines "\n"))
    master-file))

(defn- normalize-metadata [raw url]
  (let [id (:id raw)]
    {:id id
     :source :loom-web
     :url (or url (str "https://www.loom.com/share/" id))
     :title (or (:name raw) "Untitled Loom")
     :description (:description raw)
     :created-at (:createdAt raw)
     :duration-seconds (or (:playable_duration raw)
                           (get-in raw [:video_properties :duration]))
     :owner (:owner raw)
     :visibility (:visibility raw)
     :download-enabled? (:download_enabled raw)
     :raw raw}))

(defn metadata [opts url]
  (let [id (video-id url)
        payload (loom-web/graphql! opts "GetVideoSSR" (variables opts id) get-video-ssr-query)
        raw (get-in payload [:data :getVideo])]
    (case (:__typename raw)
      "VideoPasswordMissingOrIncorrect"
      (throw (ex-info "Loom video needs a password or the password is incorrect."
                      {:type :loom-password-required
                       :video-id id}))
      "PrivateVideo"
      (throw (ex-info "Loom video is private or not visible to this session."
                      {:type :loom-private-video
                       :video-id id
                       :status (:status raw)
                       :message (:message raw)}))
      (normalize-metadata raw url))))

(defn enrich-video [opts video]
  (let [id (video-id video)
        base (try
               (metadata opts (or (:url video) (str "https://www.loom.com/share/" id)))
               (catch Exception _ video))
        transcript (graphql-soft opts "FetchVideoTranscript" (variables opts id) fetch-video-transcript-query)
        chapters (graphql-soft opts "FetchChapters" (variables opts id) fetch-chapters-query)]
    (cond-> (video/merge-videos video base)
      (not (:error transcript)) (assoc :transcript-details (:fetchVideoTranscript (:data transcript)))
      (not (:error chapters)) (assoc :chapters (get-in chapters [:data :fetchVideoChapters])))))

(defn resolve-media [opts video]
  (let [id (video-id video)
        vars (assoc (variables opts id)
                    :acceptableMimes ["DASH" "M3U8" "MP4" "WEBM"])
        source (graphql-soft opts "GetVideoSource" vars get-video-source-query)
        raw-url (session-url! opts "raw-url" id)
        transcoded-url (session-url! opts "transcoded-url" id)
        cdn-url (when-not (:error source)
                  (signed-cdn-url source))
        urls (->> [{:kind :raw :url raw-url}
                   {:kind :transcoded :url transcoded-url}
                   {:kind :cdn :url cdn-url}]
                  (filter :url)
                  (reduce (fn [acc item]
                            (if (some #(= (:url %) (:url item)) acc)
                              acc
                              (conj acc item)))
                          []))]
    (when-not (seq urls)
      (throw (ex-info "No Loom media URL could be resolved."
                      {:type :loom-media-url-missing
                       :video-id id
                       :source-error (:error source)})))
    urls))

(defn- download-url! [url path]
  (with-open [in (.openStream (java.net.URL. url))
              out (io/output-stream path)]
    (io/copy in out)))

(defn- ffmpeg-args [opts input out-file]
  (vec (concat [(or (:ffmpeg-bin opts) "ffmpeg")
                "-hide_banner"
                "-loglevel" "warning"
                "-y"
                "-protocol_whitelist" "file,http,https,tcp,tls,crypto,data"
                "-allowed_extensions" "ALL"
                "-i" input
                "-c" "copy"
                "-movflags" "+faststart"
                (.getPath out-file)])))

(defn download! [opts video dir]
  (when-not (process/executable? (or (:ffmpeg-bin opts) "ffmpeg"))
    (throw (ex-info "ffmpeg is not installed or not on PATH."
                    {:type :ffmpeg-unavailable
                     :ffmpeg-bin (or (:ffmpeg-bin opts) "ffmpeg")})))
  (.mkdirs (io/file dir))
  (let [hls-dir (io/file dir ".loom-hls")]
    (try
      (let [media (resolve-media opts video)
            preferred (or (some #(when (#{"mp4" "webm" "mov" "mkv"} (ext-from-url (:url %))) %) media)
                          (first media))
            ext (or (ext-from-url (:url preferred)) "mp4")
            out-file (io/file dir (str "video." (if (#{"m3u8" "mpd"} ext) "mp4" ext)))
            input (if (= "m3u8" ext)
                    (.getPath (local-hls-playlist! (:url preferred) dir))
                    (:url preferred))
            result (process/run! (ffmpeg-args opts input out-file))]
        (if (and (zero? (:exit result)) (.exists out-file) (pos? (.length out-file)))
          {:status :downloaded
           :resolver :loom-media
           :media-kind (:kind preferred)
           :files (->> (file-seq (io/file dir))
                       (filter #(.isFile %))
                       (remove #(str/starts-with? (.getPath %) (.getPath hls-dir)))
                       (map #(.getName %))
                       sort
                       vec)}
          {:status :skipped
           :reason :download-failed
           :resolver :loom-media
           :media-kind (:kind preferred)
           :message (str/trim (or (:err result) (:out result)))}))
      (finally
        (when (.exists hls-dir)
          (doseq [f (reverse (file-seq hls-dir))]
            (io/delete-file f true)))))))

(defn write-sidecars! [opts video dir]
  (let [details (:transcript-details video)
        source-url (:source_url details)
        captions-url (:captions_source_url details)]
    (when details
      (json/write-json-file! (io/file dir "transcript-details.json") details))
    (when captions-url
      (try
        (download-url! captions-url (io/file dir "captions.vtt"))
        (catch Exception _ nil)))
    (when source-url
      (try
        (download-url! source-url (io/file dir "transcript.json"))
        (catch Exception _ nil)))))
