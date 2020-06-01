(ns robertluo.remote-pull-test
  (:require
   [robertluo.remote-pull :as sut]
   [clojure.test :refer [deftest is]]))

(deftest model->handler
  (let [handler (sut/model->handler (constantly {:foo "bar"}))]
    (is (= {:status 200}
           (-> (handler {:headers {"content-type" "application/edn"}
                         :body ":foo"})
               (select-keys [:status]))))))