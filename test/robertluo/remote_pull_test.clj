(ns robertluo.remote-pull-test
  (:require
   [robertluo.remote-pull :as sut]
   [clojure.test :refer [deftest testing is]]))

(deftest model->handler
  (let [handler (sut/model->handler (constantly {:foo "bar"}))]
    (testing "Returns the handler from the model."
      (is (= {:status 200}
             (-> (handler {:headers {"content-type" "application/edn"}
                           :body    (str '{:foo ?})})
                 (select-keys [:status])))))))

(deftest remote-pull
  (testing "when remote returns 200, return its body decoded"
    (let [handler (sut/model->handler (constantly  {:a 2}))]
      (is (= [{:a 2} {'?a 2}]
             (sut/remote-pull handler '{:a ?a} "application/edn"))))))
