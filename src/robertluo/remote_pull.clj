(ns robertluo.remote-pull
  "Remotely pull server and client"
  (:require
   [robertluo.remote-pull.format :as impl]))

(defn model->handler
  "Returns a ring handler that will:
    1. pull on the model-maker
    1. encode/decode format
    1. handle with exception
   
   model-maker is a function takes ring request as its argument, 
   returns a map as pullable model."
  [model-maker]
  (-> model-maker
      (impl/with-pull :body-params)
      impl/with-format
      impl/with-exception))

(defn remote-pull
  "Remotely pull a server."
  [post-fn pattern content-type]
  (impl/remote-pull post-fn pattern content-type))