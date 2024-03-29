(ns build
  "Build script for this project"
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as cb]))

(def lib 'robertluo/remote-pull)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def url "https://github.com/robertluo/remote-pull")

(defn tests
  [opts]
  (cb/run-tests opts))

(defn ci
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (cb/clean)
      (cb/run-tests)
      (cb/jar)))

(defn deploy
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (cb/deploy)))
