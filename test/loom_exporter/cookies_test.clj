(ns loom-exporter.cookies-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [loom-exporter.cookies :as cookies]))

(deftest netscape-cookie-file-becomes-cookie-header
  (let [f (java.io.File/createTempFile "loom-cookies" ".txt")]
    (try
      (spit f (str "# Netscape HTTP Cookie File\n"
                   ".loom.com\tTRUE\t/\tTRUE\t0\tsession\tabc\n"
                   "#HttpOnly_www.loom.com\tFALSE\t/\tTRUE\t0\tauth\txyz\n"
                   ".example.com\tTRUE\t/\tTRUE\t0\tignored\tnope\n"))
      (is (= "session=abc; auth=xyz"
             (cookies/cookie-file->header (.getPath f) "loom.com")))
      (finally
        (.delete f)))))

(deftest raw-cookie-header-file-is-supported
  (let [f (java.io.File/createTempFile "loom-raw-cookie" ".txt")]
    (try
      (spit f "Cookie: session=abc; auth=xyz\n")
      (is (= "session=abc; auth=xyz"
             (cookies/cookie-file->header (.getPath f) "loom.com")))
      (finally
        (.delete f)))))
