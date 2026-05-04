(ns loom-exporter.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loom-exporter.archive :as archive]
            [loom-exporter.json :as json]
            [loom-exporter.loom-media :as loom-media]
            [loom-exporter.loom-web :as loom-web]
            [loom-exporter.util :as util]
            [loom-exporter.video :as video]))

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
        from-loom-web (when (or (:loom-web opts)
                                (and (empty? urls)
                                     (or (:cookie opts) (:cookie-file opts))))
                        (loom-web/query-videos opts))]
    (video/dedupe-videos (concat from-loom-web from-urls))))

(defn inventory! [opts]
  (let [videos (discover-videos opts)
        manifest (archive/manifest videos)]
    (.mkdirs (io/file (:out opts)))
    (archive/write-manifest! (:out opts) manifest)
    {:status :ok
     :video-count (count videos)
     :manifest (.getPath (archive/manifest-path (:out opts)))}))

(defn videos-from-input [opts]
  (cond
    (:manifest opts)
    (:videos (json/read-json-file (:manifest opts)))

    (:archive opts)
    (let [manifest (archive/read-manifest (:archive opts))]
      (when-not manifest
        (throw (ex-info "Archive manifest.json was not found."
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
       (archive/complete-video-file? (archive/video-dir (:out opts) video))))

(defn export-video! [opts video]
  (let [dir (archive/video-dir (:out opts) video)
        _ (.mkdirs dir)
        video (loom-media/enrich-video opts video)
        _ (archive/write-transcript! dir (:transcript video))
        _ (loom-media/write-sidecars! opts video dir)
        status (cond
                 (:skip-video opts)
                 {:status :metadata-only :reason :video-download-disabled}

                 (not (:url video))
                 {:status :skipped :reason :missing-url}

                 (existing-download? opts video)
                 {:status :already-downloaded}

                 :else
                 (try
                   (loom-media/download! opts video dir)
                   (catch Exception e
                     {:status :skipped
                      :reason (or (:type (ex-data e)) :download-error)
                      :message (.getMessage e)})))]
    (archive/write-video-metadata! (:out opts) video status)
    (archive/write-readme! dir video status)
    (assoc video :export status :archive-path (.getPath dir))))

(defn export! [opts]
  (let [videos (if (or (:manifest opts) (:archive opts))
                 (videos-from-input opts)
                 (if-let [existing (archive/read-manifest (:out opts))]
                   (:videos existing)
                   (discover-videos opts)))
        exported (doall (map #(export-video! opts %) videos))
        manifest (assoc (archive/manifest exported)
                        :exported-at (util/now-iso))]
    (archive/write-manifest! (:out opts) manifest)
    {:status :ok
     :video-count (count exported)
     :downloaded (count (filter #(#{:downloaded :already-downloaded}
                                  (get-in % [:export :status]))
                                exported))
     :skipped (count (filter #(= :skipped (get-in % [:export :status]))
                             exported))
     :manifest (.getPath (archive/manifest-path (:out opts)))}))

(defn export-selected! [opts videos]
  (let [exported (doall (map #(export-video! opts %) videos))
        manifest (assoc (archive/manifest exported)
                        :exported-at (util/now-iso)
                        :selection true)]
    (archive/write-manifest! (:out opts) manifest)
    {:status :ok
     :video-count (count exported)
     :downloaded (count (filter #(#{:downloaded :already-downloaded}
                                  (get-in % [:export :status]))
                                exported))
     :skipped (count (filter #(= :skipped (get-in % [:export :status]))
                             exported))
     :manifest (.getPath (archive/manifest-path (:out opts)))}))

(defn- status-key [x]
  (cond
    (keyword? x) x
    (string? x) (keyword (str/replace x #"^:" ""))
    :else nil))

(defn verify! [opts]
  (let [manifest (archive/read-manifest (:archive opts))
        _ (when-not manifest
            (throw (ex-info "Archive manifest.json was not found."
                            {:type :manifest-missing
                             :archive (:archive opts)})))
        videos (:videos manifest)
        checks (mapv
                (fn [video]
                  (let [dir (archive/video-dir (:archive opts) video)
                        metadata (io/file dir "metadata.json")
                        video-file? (archive/complete-video-file? dir)
                        status (status-key (get-in video [:export :status]))]
                    {:id (:id video)
                     :title (:title video)
                     :metadata? (.exists metadata)
                     :video-file? (boolean video-file?)
                     :ok? (and (.exists metadata)
                               (or (#{:metadata-only :skipped} status)
                                   video-file?))}))
                videos)]
    {:status (if (every? :ok? checks) :ok :failed)
     :video-count (count checks)
     :failed (vec (remove :ok? checks))}))
