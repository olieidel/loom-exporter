(ns loom-exporter.tui
  (:require [clojure.string :as str]))

(defn- truncate [s n]
  (let [s (str (or s ""))]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (- n 1))) "…"))))

(defn- parse-int [s]
  (try
    (Integer/parseInt s)
    (catch NumberFormatException _
      nil)))

(defn- parse-token [token max-count]
  (if-let [[_ a b] (re-matches #"(\d+)-(\d+)" token)]
    (let [start (parse-int a)
          end (parse-int b)]
      (cond
        (> start end) {:error (str "Invalid range: " token)}
        (or (< start 1) (> end max-count)) {:error (str "Out of range: " token)}
        :else {:indices (range (dec start) end)}))
    (if-let [n (parse-int token)]
      (if (<= 1 n max-count)
        {:indices [(dec n)]}
        {:error (str "Out of range: " token)})
      {:error (str "Invalid selection: " token)})))

(defn parse-selection [input max-count]
  (let [input (str/trim (str input))
        lower (str/lower-case input)]
    (cond
      (#{"" "q" "quit" "cancel"} lower) {:action :cancel}
      (= "all" lower) {:action :select :indices (set (range max-count))}
      (= "none" lower) {:action :select :indices #{}}
      :else
      (let [tokens (->> (str/split lower #",")
                        (map str/trim)
                        (remove str/blank?))
            parsed (map #(parse-token % max-count) tokens)
            error (some :error parsed)]
        (if error
          {:action :error :message error}
          {:action :select
           :indices (set (mapcat :indices parsed))})))))

(defn print-video-picker [videos]
  (println)
  (println "Available videos")
  (println "----------------")
  (doseq [[idx video] (map-indexed vector videos)]
    (println (format "%3d. %-10s %-9s %s"
                     (inc idx)
                     (truncate (:visibility video) 10)
                     (truncate (name (or (:source video) "")) 9)
                     (truncate (:title video) 80)))))

(defn prompt-selection [videos]
  (print-video-picker videos)
  (println)
  (println "Select videos to download: 1,3-5,all")
  (println "Enter q or blank to cancel.")
  (loop []
    (print "> ")
    (flush)
    (let [result (parse-selection (read-line) (count videos))]
      (case (:action result)
        :cancel nil
        :select (:indices result)
        :error (do
                 (println (:message result))
                 (recur))))))

(defn confirm? [selected-count out]
  (println)
  (println (str "Download " selected-count " videos to " out "? [y/N]"))
  (print "> ")
  (flush)
  (#{"y" "yes"} (str/lower-case (str/trim (str (read-line))))))

