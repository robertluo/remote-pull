(ns robertluo.remote-pull.format
  (:require
   [robertluo.pullable :refer [pull]]
   [clojure.edn :as edn]
   [byte-streams :as bs]
   [cognitect.transit :as transit]))

(defprotocol Formatter
  (-encode
    [formatter output]
    "returns an OutputStream with output write on it")
  (-decode
    [formatter input]
    "returns data from InputStream input"))

(defn decode
  [formatter content]
  (try
    (-decode formatter content)
    (catch Exception ex
      (throw (ex-info "Decode error" {:cause :decode :exception ex})))))

(defmulti create-formatter
  "create formatter from headers"
  (fn [req] (get-in req [:headers "content-type"])))

(defn with-format
  [handler]
  (fn [req]
    (let [formatter (create-formatter req)
          req       (assoc req :body-params (some->> (:body req)
                                                     (decode formatter)))]
      (-> (handler req)
          (update :body #(-encode formatter %))))))

(defn with-pull
  [model-maker pattern-extractor]
  (fn [req]
    (if-let [pattern (pattern-extractor req)]
      (let [model (model-maker req)]
        {:status 200
         :body   (pull model pattern)})
      (throw (ex-info "No pattern" {:req req})))))

(defn with-exception
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch clojure.lang.ExceptionInfo ex
        {:status 400
         :body (str (assoc (ex-data ex) :message (ex-message ex)))})
      (catch Exception ex
        {:status 500
         :body (str ex)}))))

(defn async-wrapper
  [handler]
  (fn ([req]
       (handler req))
    ([req respond raise]
     (respond ((async-wrapper handler) req)))))

;;================
;; Client

(defn remote-pull
  [post-fn pattern content-type]
  (let [headers   {:headers {"content-type" content-type}}
        formatter (create-formatter headers)
        resp      (post-fn (merge headers {:body (-encode formatter pattern)}))
        status    (:status resp)]
    (if (= status 200)
      (some->> (:body resp) (-decode formatter))
      (throw (ex-info "Request error" {:resp resp})))))

;;==========================
;; Implementation of formatters

(defrecord EdnFormatter []
  Formatter
  (-encode
    [_ output]
    (-> (pr-str output)
        (bs/to-input-stream)))
  (-decode
    [_ input]
    (-> input
        bs/to-reader
        (java.io.PushbackReader.)
        edn/read)))

(defrecord TransitFormatter [type]
  Formatter
  (-encode
    [_ content]
    (let [^java.io.OutputStream out (java.io.ByteArrayOutputStream. 4096)
          writer (transit/writer out type)]
      (transit/write writer content)
      (bs/to-input-stream (.toByteArray out))))
  (-decode
    [_ content]
    (-> content
        bs/to-input-stream
        (transit/reader type)
        (transit/read))))

(defrecord SseFormatter []
  Formatter
  (-encode
    [_ output]
    (-> (pr-str output)
        (bs/to-input-stream)))
  (-decode
    [_ input]
    ;; should extend StreamableResponseBody
    input))

;;=============================
;; Factory to create formatter

(defmethod create-formatter "application/edn"
  [_]
  (EdnFormatter.))

(defmethod create-formatter "text/event-stream"
  [_]
  (SseFormatter.))

(defmethod create-formatter "application/transit+json"
  [_]
  (TransitFormatter. :json))

(defmethod create-formatter "application/transit+json_verbose"
  [_]
  (TransitFormatter. :json-verbose))

(defmethod create-formatter "application/transit+msgpack"
  [_]
  (TransitFormatter. :msgpack))
