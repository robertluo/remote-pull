(ns robertluo.remote-pull.format-test
  (:require
   [robertluo.remote-pull.format :as sut]
   [clojure.test :refer [deftest is testing]]
   [clojure.core.async :as async]))

;;; ---------- FORMAT ----------

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

;;; ---------- PULL ----------

(deftest with-pattern
  (let [handler (sut/with-pattern (constantly {:foo "bar"}))]
    (testing "Returns the pulled-data using the pattern"
      (is (= {:status 200 :body [{:foo "bar"} {'?foo "bar"}]}
             (handler {:body-params {:pattern '{:foo ?foo}}}))))
    (testing "If no pattern, throws exception"
      (is (thrown? clojure.lang.ExceptionInfo (handler {}))))))

(deftest with-schema
  (let [handler (sut/with-schema (constantly {:status 200
                                              :body   [{:foo "bar"} {'?foo "bar"}]})
                  {:schema-valid   [:map [:foo :string]]
                   :schema-invalid [:map [:foo :int]]})]
    (testing "No schema provided so returns data."
      (is (= {:status 200 :body [{:foo "bar"} {'?foo "bar"}]}
             (handler {:body-params {}}))))
    (testing "Schema is valid so returns data."
      (is (= {:status 200 :body [{:foo "bar"} {'?foo "bar"}]}
             (handler {:body-params {:schema :schema-valid}}))))
    (testing "If schema invalid, throws error"
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler {:body-params {:schema :schema-invalid}}))))))

(deftest with-opt
  (let [handler (sut/with-opt (constantly {:status 200
                                           :body   ['D 'V]}))]
    (testing "pattern and pattern-opt :data-only are provided"
      (is (= {:status 200 :body 'D}
             (handler {:body-params {:opt :data-only}}))))
    (testing "pattern and pattern-opt :var-only is provided"
      (is (= {:status 200 :body 'V}
             (handler {:body-params {:opt :var-only}}))))
    (testing "only pattern is provided"
      (is (= {:status 200 :body ['D 'V]}
             (handler {:body-params {}}))))))

;;; ---------- SSE ----------

(deftest with-watcher
  (let [a-data  (atom {:foo "bar"})
        ch-out  (async/chan)
        handler (sut/with-watcher
                  (constantly {:status 200 :body 'BODY})
                  (constantly a-data)
                  nil
                  ch-out)
        _       (handler {:body-params {:pattern '{:foo ?foo}}})]
    (testing "When atom changes, put! to async channel."
      (reset! a-data {:foo "bar2"})
      (is (= [{:foo "bar2"} {'?foo "bar2"}]
             (async/<!! ch-out)))
      (reset! a-data false)
      (is (nil? (async/<!! ch-out))))))

;;; ---------- EXCEPTION ----------

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

;;; ---------- CLIENT ----------

(deftest remote-pull
  (testing "when remote returns 200, return its body decoded"
    (is (= :ok
           (sut/remote-pull (constantly {:status 200 :body ":ok"})
                            'PATTERN 'OPT 'SCHEMA "application/edn"))))
  (testing "when remote returns status other than 200, raises exception"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/remote-pull (constantly {:status 400 :body ":ok"})
                                  'PATTERN 'OPT 'SCHEMA "application/edn")))))

;;; ---------- FORMATTERS ----------

(deftest round-trip
  (let [edn          (sut/->EdnFormatter)
        transit-json (sut/->TransitFormatter :msgpack)
        data         '(:transact :with [[[:user/user-reg "foo" "secret" {}]
                                         [:finance/deposite "foo" 2000]]])]
    (testing "Edn round trip"
      (is (= data (->> data (sut/-encode edn) (sut/-decode edn)))))
    (testing "transit json round trip"
      (is (= data (->> data (sut/-encode transit-json) (sut/-decode transit-json)))))))
