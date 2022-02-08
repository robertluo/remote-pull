(ns robertluo.remote-pull-test
  (:require
   [robertluo.remote-pull :as sut]
   [clojure.test :refer [deftest testing is]]
   [manifold.stream :as s]))

(deftest model->handler
  (let [handler (sut/model->handler (constantly {:foo "bar"}))]
    (testing "Returns the handler from the model."
      (is (= {:status 200}
             (-> (handler {:headers {"content-type" "application/edn"}
                           :body    (str {:pattern '{:foo ?}})})
                 (select-keys [:status])))))))

(deftest remote-pull
  (testing "Remote pull data using pattern."
    (let [handler (sut/model->handler (constantly  {:a 2}))]
      (is (= [{:a 2} {'?a 2}]
             (sut/remote-pull handler '{:a ?a} nil nil "application/edn")))))
  (testing "Remote pull data using pattern and option."
    (let [handler (sut/model->handler (constantly  {:a 2}))]
      (is (= {'?a 2}
             (sut/remote-pull handler '{:a ?a} :var-only nil "application/edn")))))
  (testing "Remote pull data using pattern, option and schema."
    (let [handler (sut/model->handler
                   (constantly  {:a 2})
                   {:schema1 [:map [:a :int]]})]
      (is (= {'?a 2}
             (sut/remote-pull handler '{:a ?a} :var-only :schema1 "application/edn"))))))

(deftest remote-pull-sse
  (testing "when remote returns 200, return its body as a stream"
    (let [a-data   (atom {:a 1})
          handler  (sut/model->sse-handler
                    (constantly a-data)
                    {:schema1 [:map [:a :int]]})
          response (sut/remote-pull handler '{:a ?a} :var-only :schema1 "text/event-stream")]
      (is (= {'?a 1}
             @(s/take! response)))
      (reset! a-data {:a 2})
      (is (= {'?a 2}
             @(s/take! response)))
      (reset! a-data nil)
      (is (= :no-value @(s/take! response :no-value))))))
