(ns loom-exporter.video
  (:require [loom-exporter.util :as util]))

(defn video-key [video]
  (or (:id video) (util/loom-id-from-url (:url video)) (:url video)))

(defn merge-videos [& videos]
  (apply merge-with
         (fn [a b]
           (cond
             (nil? b) a
             (and (map? a) (map? b)) (merge a b)
             :else b))
         videos))

(defn dedupe-videos [videos]
  (->> videos
       (reduce (fn [acc video]
                 (let [k (video-key video)]
                   (if k
                     (update acc k #(if % (merge-videos % video) video))
                     (assoc acc (str (java.util.UUID/randomUUID)) video))))
               {})
       vals
       vec))
