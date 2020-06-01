(ns robertluo.remote-pull
  "Remotely pull server and client"
  (:require
   [robertluo.remote-pull.format :as impl]
   [aleph.http :as http]))

(defn server
  "Start a remote-pull server.
   model-maker is a function takes ring request as its argument, returns a map as pullable model."
  [model-maker options]
  (let [handler (-> model-maker
                    (impl/with-pull :body-params)
                    impl/with-format
                    impl/with-exception)]
    (http/start-server handler options)))

(defn remote-pull
  "Remotely pull a server."
  [content-type url pattern]
  (impl/remote-pull content-type url pattern))