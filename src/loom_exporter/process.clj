(ns loom-exporter.process
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

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
        out-result (promise)
        err-result (promise)
        out-thread (Thread. #(deliver out-result (slurp (.getInputStream process))))
        err-thread (Thread. #(deliver err-result (slurp (.getErrorStream process))))
        _ (.start out-thread)
        _ (.start err-thread)
        exit (.waitFor process)]
    {:exit exit
     :out @out-result
     :err @err-result}))

(defn- read-lines! [stream handler]
  (with-open [reader (io/reader stream)]
    (let [lines (atom [])]
      (doseq [line (line-seq reader)]
        (swap! lines conj line)
        (when handler
          (handler line)))
      (str/join "\n" @lines))))

(defn run-with-line-handlers!
  "Runs argv and invokes handlers for stdout/stderr lines as they arrive.
  Returns {:exit n :out s :err s}. Does not throw on non-zero."
  [argv {:keys [out-line err-line]}]
  (let [pb (ProcessBuilder. ^java.util.List (vec argv))
        process (.start pb)
        out-result (promise)
        err-result (promise)
        out-thread (Thread. #(deliver out-result (read-lines! (.getInputStream process) out-line)))
        err-thread (Thread. #(deliver err-result (read-lines! (.getErrorStream process) err-line)))
        _ (.start out-thread)
        _ (.start err-thread)
        exit (.waitFor process)]
    {:exit exit
     :out @out-result
     :err @err-result}))

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
