(ns robertluo.remote-pull.format-test
  (:require
   [robertluo.remote-pull.format :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest round-trip
  (let [edn          (sut/->EdnFormatter)
        transit-json (sut/->TransitFormatter :msgpack)
        data         '(:transact :with [[[:user/user-reg "foo" "secret" {}]
                                         [:finance/deposite "foo" 2000]]])]
    (testing "Edn round trip"
      (is (= data (->> data (sut/-encode edn) (sut/-decode edn)))))
    (testing "transit json round trip"
      (is (= data (->> data (sut/-encode transit-json) (sut/-decode transit-json)))))))

(deftest with-pull
  (let [model   {:foo "bar"
                 :baz [{:int 8} {:int 9}]}
        handler (sut/with-pull (constantly model) :pattern)]
    (testing "handler using model to pull data and returns a ring repsonse"
      (is (= {:status 200
              :body   {:foo "bar"}}
             (handler {:pattern [:foo]}))))))