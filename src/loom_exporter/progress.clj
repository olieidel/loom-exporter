(ns loom-exporter.progress
  (:require [clojure.string :as str]))

(defn- terminal? []
  (and (System/console)
       (not= "dumb" (System/getenv "TERM"))))

(defn- truncate [s n]
  (let [s (str (or s ""))]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (- n 1))) "~"))))

(defn- clamp [n low high]
  (-> n (max low) (min high)))

(defn- bar [percent width]
  (let [pct (clamp (double (or percent 0.0)) 0.0 100.0)
        filled (int (Math/round (* width (/ pct 100.0))))]
    (str "[" (apply str (repeat filled "#"))
         (apply str (repeat (- width filled) "-"))
         "]")))

(defn- line-for [item]
  (let [percent (or (:percent item) 0.0)
        phase (or (:phase item) "queued")
        title (truncate (:title item) 40)]
    (format "%6.1f%% %s %-13s %s"
            (double percent)
            (bar percent 24)
            (truncate phase 13)
            title)))

(defn- render! [state]
  (binding [*out* *err*]
    (let [{:keys [items lines]} @state
          values (->> @items
                      vals
                      (sort-by (juxt #(if (:done? %) 1 0) :order)))
          next-lines (max 1 (count values))]
      (when (pos? lines)
        (print (str "\u001b[" lines "A")))
      (doseq [line (if (seq values)
                     (map line-for values)
                     ["Preparing downloads..."])]
        (print "\r\u001b[2K")
        (println line))
      (flush)
      (swap! state assoc :lines next-lines))))

(defn start! []
  (if-not (terminal?)
    {:update (fn [_ _])
     :finish (fn [])}
    (let [items (atom {})
          running? (atom true)
          state (atom {:items items :lines 0})
          order (atom 0)
          ensure-order (fn [current]
                         (if (:order current)
                           current
                           (assoc current :order (swap! order inc))))
          done (promise)
          renderer (Thread.
                    (fn []
                      (try
                        (binding [*out* *err*]
                          (print "\u001b[?25l")
                          (flush))
                        (while @running?
                          (render! state)
                          (Thread/sleep 200))
                        (render! state)
                        (binding [*out* *err*]
                          (print "\u001b[?25h")
                          (flush))
                        (finally
                          (deliver done true)))))]
      (.setDaemon renderer true)
      (.start renderer)
      {:update (fn [id data]
                 (swap! items update id #(merge (ensure-order (or % {})) data)))
       :finish (fn []
                 (reset! running? false)
                 @done
                 (binding [*out* *err*]
                   (println)
                   (flush)))})))
