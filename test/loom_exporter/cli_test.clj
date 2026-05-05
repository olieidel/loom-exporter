(ns loom-exporter.cli-test
  (:require [clojure.test :refer [deftest is]]
            [loom-exporter.cli :as cli]))

(def parse-command #'cli/parse-command)

(deftest list-command-accepts-renamed-options
  (let [{:keys [command options errors]} (parse-command ["list"
                                                         "--limit" "5"
                                                         "--list-format" "json"])]
    (is (= "list" command))
    (is (nil? errors))
    (is (= 5 (:limit options)))
    (is (= "json" (:list-format options)))))

(deftest removed-options-are-rejected
  (is (seq (:errors (parse-command ["list" "--first" "5"]))))
  (is (seq (:errors (parse-command ["list" "--format" "json"]))))
  (is (seq (:errors (parse-command ["list" "--loom-web"]))))
  (is (seq (:errors (parse-command ["list" "--cookie" "session=abc"])))))

(deftest command-specific-options-are-enforced
  (is (nil? (:errors (parse-command ["export" "--jobs" "2"]))))
  (is (seq (:errors (parse-command ["verify" "--jobs" "2"])))))
