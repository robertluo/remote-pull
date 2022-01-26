(ns robertluo.remote-pull.format
  (:require
   [robertluo.pullable :as pull]
   [clojure.edn :as edn]
   [byte-streams :as bs]
   [manifold.stream :as s]
   [cognitect.transit :as transit]
   [clojure.core.async :as async])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

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

(defn with-format-sse
  [handler ch-out]
  (fn [req]
    (let [formatter (create-formatter req)
          req       (assoc req :body-params (some->> (:body req)
                                                     (decode formatter)))]
      (-> (handler req)
          (assoc :body (-encode formatter ch-out))))))

(defn pull-model
  [model pattern opt]
  (let [data (pull/run pattern model)]
    ((case opt :data-only first :var-only second identity) data)))

(defn with-pull
  [model-maker pattern-extractor]
  (fn [req]
    (if-let [{:keys [pattern opt]} (pattern-extractor req)]
      (let [model (model-maker req)]
        {:status 200
         :body   (pull-model model pattern opt)})
      (throw (ex-info "No pattern" {:req req})))))

(defn with-pull-sse
  [model-maker pattern-extractor ch-out]
  (fn [req]
    (if-let [{:keys [pattern opt]} (pattern-extractor req)]
      (let [a-model (model-maker req)
            data    (pull-model @a-model pattern opt)]
        (add-watch a-model ::sse (fn [_ _ _ new-v]
                                   (if new-v
                                     (let [data (pull-model new-v pattern opt)]
                                       (async/put! ch-out data))
                                     (async/close! ch-out))))
        (async/put! ch-out data)
        {:status 200})
      (throw (ex-info "No pattern" {:req req})))))

(defn with-exception
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch clojure.lang.ExceptionInfo ex
        {:status 400
         :body   (str (assoc (ex-data ex) :message (ex-message ex)))})
      (catch Exception ex
        {:status 500
         :body   (str ex)}))))

;;================
;; Client

(defn remote-pull
  [post-fn pattern opt content-type]
  (let [headers   {:headers {"content-type" content-type}}
        formatter (create-formatter headers)
        resp      (post-fn
                   (merge headers
                          {:body (-encode formatter {:pattern pattern :opt opt})}))
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
    [_ content]
    (if (instance? ManyToManyChannel content)
      (s/map pr-str content)
      (-> (pr-str content)
          (bs/to-input-stream))))
  (-decode
    [_ content]
    (if (s/stream? content)
      (s/map edn/read-string content)
      (-> content
          bs/to-reader
          (java.io.PushbackReader.)
          edn/read))))

;;=============================
;; Factory to create formatter

(defmethod create-formatter "application/edn"
  [_]
  (EdnFormatter.))

(defmethod create-formatter "application/transit+json"
  [_]
  (TransitFormatter. :json))

(defmethod create-formatter "application/transit+json_verbose"
  [_]
  (TransitFormatter. :json-verbose))

(defmethod create-formatter "application/transit+msgpack"
  [_]
  (TransitFormatter. :msgpack))

(defmethod create-formatter "text/event-stream"
  [_]
  (SseFormatter.))
