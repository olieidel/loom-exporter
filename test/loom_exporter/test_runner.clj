(ns loom-exporter.test-runner
  (:gen-class)
  (:require [clojure.test :as test]
            loom-exporter.archive-test
            loom-exporter.cli-test
            loom-exporter.core-test
            loom-exporter.cookies-test
            loom-exporter.tui-test
            loom-exporter.video-test))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'loom-exporter.archive-test
                                             'loom-exporter.cli-test
                                             'loom-exporter.core-test
                                             'loom-exporter.cookies-test
                                             'loom-exporter.tui-test
                                             'loom-exporter.video-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
