(ns robertluo.remote-pull.format
  (:require
   [sg.flybot.pullable :as pull]
   [clojure.edn :as edn]
   [byte-streams :as bs]
   [manifold.stream :as s]
   [cognitect.transit :as transit]
   [clojure.core.async :as async]
   [malli.core :as m]
   [malli.error :as me])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

;;; ---------- FORMAT ----------

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

;;; ---------- PULL ----------

(defn with-pattern
  [model-maker]
  (fn [req]
    (if-let [pattern (-> req :body-params :pattern)]
      (let [query-fn (pull/query pattern)
            model (model-maker req)]
        {:status 200
         :body   (query-fn (if (instance? clojure.lang.Atom model) @model model))})
      (throw (ex-info "No pattern" {:req req})))))

^:rct/test
(comment
  ((with-pattern (constantly {:deps {:paths ["src"]}}))
   {:body-params {:pattern '{:deps {:paths ?global}}}})
  ;; => {:status 200, :body {?global ["src"], &? {:deps {:paths ["src"]}}}}
  )

(defn- data-only
  [result]
  (get result '&?))

(defn- var-only
  [result]
  (dissoc result '&?))

(defn with-schema
  [handler all-schemas]
  (fn [req]
    (if-let [schema (get all-schemas (-> req :body-params :schema))]
      (let [validator (m/validator schema)
            resp      (handler req)
            data      (->> resp :body data-only)]
        (if (validator data)
          resp
          (throw
           (let [err (m/explain schema data)]
             (ex-info (str (me/humanize err)) {:req req :error err})))))
      (handler req))))

^:rct/test
(comment
  (def test-schema-handler (with-schema (constantly {:body '{?global ["src"]
                                                             &?      {:deps {:paths ["src"]}}}})
                             {:path [:map [:deps :map]]}))
  ;; do not need to validate
  (test-schema-handler {:body-params {}}) ;; => {:body {?global ["src"], &? {:deps {:paths ["src"]}}}}

  ;; validate ok, return response
  (test-schema-handler {:body-params {:schema :path}}) ;; => {:body {?global ["src"], &? {:deps {:paths ["src"]}}}}

  ;; validate fail, throw ex
  ((with-schema (constantly {:body '{?global ["src"], &? {:deps {:paths ["src"]}}}})
     {:path [:map [:deps :string]]})
   {:body-params {:schema :path}})
  ;; throws=>>
  #:error{:class   clojure.lang.ExceptionInfo
          :data    map?
          :message #"should be a string"})

(defn with-opt
  [handler]
  (fn [req]
    (let [opt (-> req :body-params :opt)
          transform (case opt
                      :data-only data-only
                      :var-only  var-only
                      identity)]
      (-> (handler req)
          (update :body transform)))))

^:rct/test
(comment
  (def test-opt-handler (with-opt (constantly {:body '{?global ["src"], &? {:deps {:paths ["src"]}}}})))
  (test-opt-handler {:body-params {}}) ;; => {:body {?global ["src"], &? {:deps {:paths ["src"]}}}}
  (test-opt-handler {:body-params {:opt :data-only}}) ;; => {:body {:deps {:paths ["src"]}}}
  (test-opt-handler {:body-params {:opt :var-only}}) ;; => {:body {?global ["src"]}}
  )

(defn with-pull
  [model-maker schemas]
  (-> (with-pattern model-maker)
      (with-schema schemas)
      with-opt))

;;; ---------- SSE ----------

(defn with-channel
  [handler ch-out]
  (fn [req]
    (let [resp (handler req)]
      (async/put! ch-out (:body resp))
      resp)))

(defn with-watcher
  [handler model-maker schemas ch-out]
  (fn [req]
    (let [a-model (model-maker req)]
      (add-watch a-model ::sse  (fn [_ _ _ new-v]
                                  (if new-v
                                    ((-> (with-pull model-maker schemas)
                                         (with-channel ch-out)) req)
                                    (async/close! ch-out)))))
    (handler req)))

(defn with-sse
  [handler model-maker schemas ch-out]
  (-> handler
      (with-channel ch-out)
      (with-watcher model-maker schemas ch-out)))

;;; ---------- EXCEPTION ----------

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

;;; ---------- CLIENT ----------

(defn remote-pull
  [post-fn pattern opt schema content-type]
  (let [headers   {:headers {"content-type" content-type}}
        formatter (create-formatter headers)
        resp      (post-fn
                   (merge headers
                          {:body (-encode formatter {:pattern pattern :opt opt :schema schema})}))
        status    (:status resp)]
    (if (= status 200)
      (some->> (:body resp) (-decode formatter))
      (throw (ex-info "Request error" {:resp resp})))))

;;; ---------- FORMATTERS ----------

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
