(ns build
  "Build script for this project"
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as cb]))

(defn project
  [opts]
  (-> {:lib     'io.github.robertluo/lasagna-QL
       :version (format "0.3.%s" (b/git-count-revs nil))
       :scm     "https://github.com/robertluo/remote-pull"}
      (merge opts)))

(defn tests
  [opts]
  (-> opts (project) (cb/run-tests)))

(defn ci
  [opts]
  (-> opts (project) (cb/clean) tests (cb/jar)))

(defn deploy
  [opts]
  (-> opts (project) (cb/deploy)))
