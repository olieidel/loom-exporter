(ns loom-exporter.util
  (:require [clojure.string :as str]))

(defn blank->nil [s]
  (when-not (str/blank? (str s))
    s))

(defn safe-id [s]
  (-> (or s "unknown")
      str
      (str/replace #"[^A-Za-z0-9_.-]+" "_")
      (str/replace #"_{2,}" "_")
      (str/replace #"(^_+|_+$)" "")
      blank->nil
      (or "unknown")))

(defn slug [s]
  (let [base (-> (or s "untitled")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"-{2,}" "-")
                 (str/replace #"(^-+|-+$)" ""))]
    (subs (or (blank->nil base) "untitled")
          0
          (min 80 (count (or (blank->nil base) "untitled"))))))

(defn loom-id-from-url [url]
  (some->> url
           (re-find #"loom\.com/(?:share|embed|recording)/([A-Za-z0-9]+)")
           second))

(defn now-iso []
  (str (java.time.Instant/now)))

(defn ensure-vector [x]
  (cond
    (nil? x) []
    (vector? x) x
    (sequential? x) (vec x)
    :else [x]))

(defn deep-values
  "Returns every map value reachable under k from nested maps/vectors."
  [k x]
  (letfn [(walk [v]
            (lazy-seq
             (cond
               (map? v) (concat (when (contains? v k) [(get v k)])
                                (mapcat walk (vals v)))
               (sequential? v) (mapcat walk v)
               :else nil)))]
    (walk x)))
