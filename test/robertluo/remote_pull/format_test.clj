(ns robertluo.remote-pull.format-test
  (:require
   [robertluo.remote-pull.format :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest decode
  (testing "delegate to format"
    (is (= :a (sut/decode (sut/->EdnFormatter) ":a"))))
  (testing "throws exception if malfored"
    (is (thrown? clojure.lang.ExceptionInfo (sut/decode (sut/->EdnFormatter) "]")))))

(deftest with-format
  (let [handler (sut/with-format identity)]
    (is (= [{:foo 3} "{:foo 3}"]
           (->> (handler {:headers {"content-type" "application/edn"} :body "{:foo 3}"})
                ((juxt :body-params #(sut/-decode (sut/->EdnFormatter) (:body %)))))))))

(deftest with-pull
  (let [handler (sut/with-pull (constantly {:foo "bar"}) :body-params)]
    (testing "pull with pattern returns result"
      (is (= {:foo "bar"}
             (handler {:body-params :foo}))))
    (testing "if no pattern, throw exception"
      (is (thrown? clojure.lang.ExceptionInfo (handler {}))))))

(deftest with-exception
  (testing "for any ExceptionInfo throws, returns a 400 error response"
    (is (= {:status 400 :body "{:message \"\"}"}
           ((sut/with-exception (fn [_] (throw (ex-info "" {})))) {}))))
  (testing "for other exception, returns a 500 error"
    (is (= {:status 500 :body "java.lang.Exception: "}
           ((sut/with-exception (fn [_] (throw (Exception. "")))) {}))))
  (testing "default just call handler"
    (is (= {}
           ((sut/with-exception identity) {})))))

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