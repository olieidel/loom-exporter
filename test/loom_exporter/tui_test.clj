(ns loom-exporter.tui-test
  (:require [clojure.test :refer [deftest is]]
            [loom-exporter.tui :as tui]))

(deftest parse-selection-supports-indices-ranges-and-all
  (is (= {:action :select :indices #{0 2 3 4}}
         (tui/parse-selection "1,3-5" 8)))
  (is (= {:action :select :indices #{0 1 2}}
         (tui/parse-selection "all" 3)))
  (is (= {:action :select :indices #{}}
         (tui/parse-selection "none" 3))))

(deftest parse-selection-reports-cancel-and-errors
  (is (= {:action :cancel}
         (tui/parse-selection "" 3)))
  (is (= :error (:action (tui/parse-selection "4" 3))))
  (is (= :error (:action (tui/parse-selection "3-1" 3)))))
