(ns robertluo.remote-pull
  "Remotely pull server and client"
  (:require
   [robertluo.remote-pull.format :as impl]))

(defn model->handler
  "Returns a ring handler that will:
    1. pull on the model-maker
    2. encode/decode format
    3. handle with exception

   model-maker is a function takes ring request as its argument,
   returns a map as pullable model."
  [model-maker]
  (-> model-maker
      (impl/with-pull :body-params)
      impl/with-format
      impl/with-exception))

(defn remote-pull
  "Pull the requested data from a server using:
  - `post-fn`     : handler that takes the request
  - `pattern`     : pull pattern to fetch the data
  - `opt`         : optional keyword to get data-only or var-only parts from [data var]
  - `content-type`: to be added to the header."
  ([post-fn pattern content-type]
   (remote-pull post-fn pattern nil content-type))
  ([post-fn pattern opt content-type]
   (impl/remote-pull post-fn pattern opt content-type)))
