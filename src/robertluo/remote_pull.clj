(ns robertluo.remote-pull
  "Remotely pull server and client"
  (:require
   [robertluo.remote-pull.format :as impl]
   [clojure.core.async :as async]))

(defn model->handler
  "Returns a ring handler that will:
    1. pull on the model-maker
    2. optionally validate the pulled data with malli schema
    3. optionally keep only data part or var part of the pulled data
    4. encode/decode format
    5. handle with exception

   `model-maker`: function that takes a ring request as its argument, returns a map as pullable model.
   `all-schemas`: optional map of Malli schemas to validate the pulled data."
  ([model-maker]
   (model->handler model-maker nil))
  ([model-maker all-schemas]
   (-> model-maker
       (impl/with-pull all-schemas)
       impl/with-format
       impl/with-exception)))

(defn model->sse-handler
  "Returns a ring handler that will:
    6. supports SSE via core async chans and manifold stream."
  ([model-maker]
   (model->sse-handler model-maker nil))
  ([model-maker all-schemas]
   (let [ch-out (async/chan)]
     (-> model-maker
         (impl/with-pull all-schemas)
         (impl/with-sse model-maker all-schemas ch-out)
         (impl/with-format-sse ch-out)
         impl/with-exception))))

(defn remote-pull
  "Pull the requested data from a server using:
  - `post-fn`     : handler that takes the request
  - `pattern`     : pull pattern to fetch the data
  - `opt`         : optional keyword to get data-only or var-only parts from [data var]
  - `schema`      : optional keyword to validate data using a Malli schema
  - `content-type`: to be added to the header."
  ([post-fn pattern content-type]
   (remote-pull post-fn pattern nil nil content-type))
  ([post-fn pattern opt content-type]
   (impl/remote-pull post-fn pattern opt nil content-type))
  ([post-fn pattern opt schema content-type]
   (impl/remote-pull post-fn pattern opt schema content-type)))
