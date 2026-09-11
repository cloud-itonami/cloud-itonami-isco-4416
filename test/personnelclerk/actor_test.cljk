(ns personnelclerk.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [personnelclerk.actor :as actor]
            [personnelclerk.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-position! st {:position-id "P-1" :client-id "client-1"
                                  :name "warehouse-associate"
                                  :required-fields #{"tax-id"}
                                  :background-check-required? true})
    st))

(deftest commits-a-complete-cleared-onboarding
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-onboarding :stake :low
                 :position-id "P-1" :submitted-fields #{"tax-id"}
                 :background-check-cleared true}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-uncleared-onboarding
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-onboarding :stake :low
                 :position-id "P-1" :submitted-fields #{"tax-id"}
                 :background-check-cleared false}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-provisional-start-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-provisional-start :stake :high
                 :position-id "P-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
