(ns loom-exporter.cookies
  (:require [clojure.string :as str]))

(defn- netscape-cookie-line? [line]
  (and (not (str/blank? line))
       (not (str/starts-with? line "# "))
       (not (str/starts-with? line "# Netscape"))))

(defn- parse-netscape-line [line]
  (let [http-only? (str/starts-with? line "#HttpOnly_")
        normalized (cond-> line http-only? (subs 10))
        parts (str/split normalized #"\t")]
    (when (>= (count parts) 7)
      (let [[domain _flag path _secure expires name value] parts]
        {:domain domain
         :path path
         :expires expires
         :name name
         :value value}))))

(defn raw-cookie-header-file? [path]
  (let [content (str/trim (slurp path))]
    (and (not (str/includes? content "\t"))
         (str/includes? content "=")
         (or (str/includes? content ";") (not (str/includes? content "\n"))))))

(defn cookie-file->header
  "Builds a Cookie header from either a raw Cookie header file or Netscape cookies.txt."
  [path domain-suffix]
  (let [content (str/trim (slurp path))]
    (if (raw-cookie-header-file? path)
      (-> content
          (str/replace #"(?i)^cookie:\s*" "")
          (str/replace #"\r?\n" " ")
          str/trim)
      (->> (str/split-lines content)
           (filter netscape-cookie-line?)
           (keep parse-netscape-line)
           (filter #(str/includes? (:domain %) domain-suffix))
           (map #(str (:name %) "=" (:value %)))
           distinct
           (str/join "; ")))))
