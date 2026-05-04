(ns loom-exporter.process
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]))

(defn executable? [cmd]
  (let [candidate (java.io.File. cmd)]
    (if (or (.isAbsolute candidate) (str/includes? cmd java.io.File/separator))
      (and (.exists candidate) (.canExecute candidate) (.isFile candidate))
      (let [path (System/getenv "PATH")
            dirs (when path (str/split path #":"))]
        (boolean
         (some (fn [dir]
                 (let [f (java.io.File. dir cmd)]
                   (and (.exists f) (.canExecute f) (.isFile f))))
               dirs))))))

(defn run!
  "Runs argv and returns {:exit n :out s :err s}. Does not throw on non-zero."
  [argv]
  (let [pb (ProcessBuilder. ^java.util.List (vec argv))
        process (.start pb)
        out-future (future (slurp (.getInputStream process)))
        err-future (future (slurp (.getErrorStream process)))
        exit (.waitFor process)]
    {:exit exit
     :out @out-future
     :err @err-future}))

(defn run-ok! [argv]
  (let [{:keys [exit out err] :as result} (run! argv)]
    (if (zero? exit)
      result
      (throw (ex-info (str "Command failed: " (str/join " " argv))
                      {:type :process-failed
                       :argv argv
                       :exit exit
                       :out out
                       :err err})))))
