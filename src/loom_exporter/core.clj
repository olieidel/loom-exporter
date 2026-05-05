(ns loom-exporter.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom-exporter.archive :as archive]
            [loom-exporter.loom-media :as loom-media]
            [loom-exporter.loom-web :as loom-web]
            [loom-exporter.progress :as progress]
            [loom-exporter.util :as util]
            [loom-exporter.video :as video])
  (:import [java.util.concurrent Callable Executors]))

(defn- read-urls-file [path]
  (->> (str/split-lines (slurp path))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       vec))

(defn- option-urls [opts]
  (vec (concat (util/ensure-vector (:url opts))
               (when (:urls-file opts)
                 (read-urls-file (:urls-file opts))))))

(defn discover-videos [opts]
  (let [urls (option-urls opts)
        _ (when (and (empty? urls)
                     (not (:cookie-file opts)))
            (throw (ex-info "Video discovery needs --cookie-file, --url, or --urls-file."
                            {:type :discovery-source-missing})))
        from-urls (when (seq urls)
                    (doall
                     (for [url urls]
                       (try
                         (loom-media/metadata opts url)
                         (catch Exception _
                           {:id (util/loom-id-from-url url)
                            :source :url
                            :url url
                            :title "Untitled Loom"})))))
        from-loom-web (when (and (empty? urls)
                                 (:cookie-file opts))
                        (loom-web/query-videos opts))]
    (video/dedupe-videos (concat from-loom-web from-urls))))

(defn inventory! [opts]
  (let [videos (discover-videos opts)
        manifest (archive/manifest videos)]
    (.mkdirs (io/file (:out opts)))
    (archive/write-manifest! (:out opts) manifest opts)
    {:status :ok
     :video-count (count videos)
     :manifest (.getPath (archive/manifest-path (:out opts) opts))}))

(defn videos-from-input [opts]
  (cond
    (:manifest opts)
    (:videos (archive/read-data-file (:manifest opts)))

    (:archive opts)
    (let [manifest (archive/read-manifest (:archive opts) opts)]
      (when-not manifest
        (throw (ex-info "Archive manifest was not found."
                        {:type :manifest-missing
                         :archive (:archive opts)})))
      (:videos manifest))

    :else
    (discover-videos opts)))

(defn list-videos [opts]
  (let [videos (videos-from-input opts)]
    {:status :ok
     :video-count (count videos)
     :videos (vec videos)}))

(defn- existing-download? [opts video]
  (and (not (:force opts))
       (archive/complete-video-file? (archive/video-dir (:out opts) video) video)))

(defn- progress! [opts video data]
  (when-let [f (:progress-fn opts)]
    (f (or (:id video) (:url video))
       (merge {:title (:title video)}
              data))))

(defn export-video! [opts video]
  (let [dir (archive/video-dir (:out opts) video)
        _ (.mkdirs dir)
        _ (progress! opts video {:phase "metadata" :percent 0.0})
        video (loom-media/enrich-video opts video)
        _ (archive/write-transcript! dir (:transcript video) opts)
        _ (loom-media/write-sidecars! opts video dir)
        status (cond
                 (:skip-video opts)
                 {:status :metadata-only :reason :video-download-disabled}

                 (not (:url video))
                 {:status :skipped :reason :missing-url}

                 (existing-download? opts video)
                 (do
                   (progress! opts video {:phase "already done" :percent 100.0 :done? true})
                   {:status :already-downloaded})

                 :else
                 (try
                   (loom-media/download! opts video dir)
                   (catch Exception e
                     (progress! opts video {:phase "failed" :done? true})
                     {:status :skipped
                      :reason (or (:type (ex-data e)) :download-error)
                      :message (.getMessage e)})))]
    (when (and (not (:done? status))
               (#{:metadata-only :skipped} (:status status)))
      (progress! opts video {:phase (name (:status status)) :done? true}))
    (archive/write-video-metadata! (:out opts) video status opts)
    (archive/write-readme! dir video status)
    (assoc video :export status :archive-path (.getPath dir))))

(defn- jobs [opts]
  (max 1 (long (or (:jobs opts) 1))))

(defn- export-videos! [opts videos]
  (if (= 1 (jobs opts))
    (doall (map #(export-video! opts %) videos))
    (let [executor (Executors/newFixedThreadPool (jobs opts))]
      (try
        (let [futures (mapv (fn [video]
                              (.submit executor
                                       ^Callable
                                       (reify Callable
                                         (call [_]
                                           (export-video! opts video)))))
                            videos)]
          (mapv #(.get %) futures))
        (finally
          (.shutdownNow executor))))))

(defn- with-progress [opts f]
  (if (or (:skip-video opts) (:no-progress opts))
    (f opts)
    (let [{:keys [update finish]} (progress/start!)]
      (try
        (f (assoc opts :progress-fn update))
        (finally
          (finish))))))

(defn export! [opts]
  (with-progress
    opts
    (fn [opts]
      (let [videos (if (or (:manifest opts) (:archive opts))
                     (videos-from-input opts)
                     (if-let [existing (archive/read-manifest (:out opts) opts)]
                       (:videos existing)
                       (discover-videos opts)))
            exported (export-videos! opts videos)
            manifest (assoc (archive/manifest exported)
                            :exported-at (util/now-iso))]
        (archive/write-manifest! (:out opts) manifest opts)
        {:status :ok
         :video-count (count exported)
         :downloaded (count (filter #(#{:downloaded :already-downloaded}
                                      (get-in % [:export :status]))
                                    exported))
         :skipped (count (filter #(= :skipped (get-in % [:export :status]))
                                 exported))
         :manifest (.getPath (archive/manifest-path (:out opts) opts))}))))

(defn export-selected! [opts videos]
  (with-progress
    opts
    (fn [opts]
      (let [exported (export-videos! opts videos)
            manifest (assoc (archive/manifest exported)
                            :exported-at (util/now-iso)
                            :selection true)]
        (archive/write-manifest! (:out opts) manifest opts)
        {:status :ok
         :video-count (count exported)
         :downloaded (count (filter #(#{:downloaded :already-downloaded}
                                      (get-in % [:export :status]))
                                    exported))
         :skipped (count (filter #(= :skipped (get-in % [:export :status]))
                                 exported))
         :manifest (.getPath (archive/manifest-path (:out opts) opts))}))))

(defn- status-key [x]
  (cond
    (keyword? x) x
    (string? x) (keyword (str/replace x #"^:" ""))
    :else nil))

(defn verify! [opts]
  (let [manifest (archive/read-manifest (:archive opts) opts)
        _ (when-not manifest
            (throw (ex-info "Archive manifest was not found."
                            {:type :manifest-missing
                             :archive (:archive opts)})))
        videos (:videos manifest)
        checks (mapv
                (fn [video]
                  (let [dir (archive/video-dir (:archive opts) video)
                        metadata (archive/existing-metadata-file (:archive opts) video opts)
                        video-file? (archive/complete-video-file? dir video)
                        status (status-key (get-in video [:export :status]))]
                    {:id (:id video)
                     :title (:title video)
                     :metadata? (boolean metadata)
                     :video-file? (boolean video-file?)
                     :ok? (and metadata
                               (or (#{:metadata-only :skipped} status)
                                   video-file?))}))
                videos)]
    {:status (if (every? :ok? checks) :ok :failed)
     :video-count (count checks)
     :failed (vec (remove :ok? checks))}))
