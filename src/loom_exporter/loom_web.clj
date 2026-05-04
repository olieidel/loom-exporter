(ns loom-exporter.loom-web
  (:require [clojure.string :as str]
            [loom-exporter.cookies :as cookies]
            [loom-exporter.json :as json]
            [loom-exporter.util :as util]
            [loom-exporter.video :as video]))

(def graphql-url "https://www.loom.com/graphql")

(def get-looms-query
  "query GetLoomsForLibrary($limit: Int!, $cursor: String, $folderId: String, $sourceValue: String, $source: LoomsSource!, $sortType: LoomsSortType!, $sortOrder: LoomsSortOrder!, $sortGrouping: LoomsSortGrouping, $filters: [[LoomsCollectionFilter!]!], $timeRange: TimeRange) {
  getLooms {
    __typename
    ... on GetLoomsPayload {
      videos(first: $limit, after: $cursor, folderId: $folderId, sourceValue: $sourceValue, source: $source, sortType: $sortType, sortOrder: $sortOrder, sortGrouping: $sortGrouping, filters: $filters, timeRange: $timeRange) {
        edges {
          cursor
          node {
            id
            name
            visibility
            __typename
          }
          __typename
        }
        pageInfo {
          endCursor
          hasNextPage
          __typename
        }
        __typename
      }
      __typename
    }
  }
}")

(defn cookie-header [opts]
  (or (:cookie opts)
      (when (:cookie-file opts)
        (cookies/cookie-file->header (:cookie-file opts) "loom.com"))))

(defn- http-client []
  (java.net.http.HttpClient/newHttpClient))

(defn graphql! [opts operation-name variables query]
  (let [cookie (cookie-header opts)]
    (let [body (json/write-str {:operationName operation-name
                                :variables variables
                                :query query})
          request-builder (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create graphql-url))
                              (.header "content-type" "application/json")
                              (.header "accept" "*/*")
                              (.header "apollographql-client-name" "web")
                              (.header "x-loom-request-source" "loom_exporter")
                              (.header "referer" "https://www.loom.com/looms/videos"))
          request-builder (cond-> request-builder
                            (not (str/blank? cookie)) (.header "cookie" cookie))
          request (-> request-builder
                      (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                      .build)
          response (.send (http-client) request (java.net.http.HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          parsed (json/read-json-string (.body response))]
      (when-not (<= 200 status 299)
        (throw (ex-info "Loom GraphQL request failed."
                        {:type :loom-web-http-error
                         :status status
                         :body parsed})))
      (when-let [errors (seq (:errors parsed))]
        (throw (ex-info "Loom GraphQL returned errors."
                        {:type :loom-web-graphql-error
                         :errors errors})))
      parsed)))

(defn- page-variables [opts cursor]
  {:source (or (:loom-source opts) "ALL")
   :sortType (or (:loom-sort-type opts) "RECENT")
   :sortOrder (or (:loom-sort-order opts) "DESC")
   :filters (or (:loom-filters opts) [])
   :limit (or (:page-size opts) 50)
   :cursor cursor
   :folderId (:folder-id opts)
   :sourceValue (:source-value opts)
   :timeRange nil})

(defn- normalize-node [node]
  {:id (:id node)
   :source :loom-web
   :url (str "https://www.loom.com/share/" (:id node))
   :title (or (:name node) "Untitled Loom")
   :visibility (:visibility node)
   :raw node})

(defn query-videos [opts]
  (when (str/blank? (cookie-header opts))
    (throw (ex-info "Loom web inventory needs --cookie or --cookie-file."
                    {:type :loom-web-auth-missing})))
  (loop [cursor nil
         acc []
         pages 0]
    (let [payload (graphql! opts "GetLoomsForLibrary" (page-variables opts cursor) get-looms-query)
          conn (get-in payload [:data :getLooms :videos])
          edges (:edges conn)
          videos (mapv #(normalize-node (:node %)) edges)
          next-cursor (get-in conn [:pageInfo :endCursor])
          has-next? (true? (get-in conn [:pageInfo :hasNextPage]))
          combined (into acc videos)
          max-videos (:first opts)
          page-limit (:page-limit opts)]
      (cond
        (and max-videos (>= (count combined) max-videos))
        (vec (take max-videos combined))

        (and page-limit (>= (inc pages) page-limit))
        combined

        (and has-next? next-cursor)
        (recur next-cursor combined (inc pages))

        :else
        (video/dedupe-videos combined)))))
