(ns personnelclerk.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [personnelclerk.store :as store]
            [personnelclerk.governor :as governor]))

(defn- fresh-store [bg-required?]
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-position! st {:position-id "P-1" :client-id "client-1"
                                  :name "warehouse-associate"
                                  :required-fields #{"tax-id" "emergency-contact" "bank-details"}
                                  :background-check-required? bg-required?})
    st))

(defn- onboard [fields cleared]
  {:op :approve-onboarding :effect :propose :position-id "P-1"
   :submitted-fields fields :background-check-cleared cleared
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})
(def ^:private all-fields #{"tax-id" "emergency-contact" "bank-details"})

(deftest ok-complete-file-and-cleared
  (let [st (fresh-store true)
        v (governor/check req {} (onboard all-fields true) st)]
    (is (:ok? v))))

(deftest ok-with-extra-fields-beyond-required
  (testing "a superset of the required fields still satisfies completeness"
    (let [st (fresh-store false)
          v (governor/check req {} (onboard (conj all-fields "linkedin") false) st)]
      (is (:ok? v)))))

(deftest hard-on-incomplete-personnel-file
  (testing "missing fields are not clerical convenience"
    (let [st (fresh-store false)
          v (governor/check req {} (assoc (onboard #{"tax-id"} false) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :incomplete-personnel-file (:rule %)) (:violations v))))))

(deftest hard-on-background-check-not-cleared
  (testing "onboarding without clearance is a policy violation"
    (let [st (fresh-store true)
          v (governor/check req {} (assoc (onboard all-fields false) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :background-check-not-cleared (:rule %)) (:violations v))))))

(deftest ok-uncleared-when-not-required
  (testing "the background-check gate only fires when the position requires it"
    (let [st (fresh-store false)
          v (governor/check req {} (onboard all-fields false) st)]
      (is (:ok? v)))))

(deftest hard-on-unknown-position
  (let [st (fresh-store false)
        v (governor/check req {} (assoc (onboard all-fields false) :position-id "P-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-position (:rule %)) (:violations v)))))

(deftest hard-on-foreign-position
  (let [st (fresh-store false)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (onboard all-fields false) st)]
      (is (:hard? v))
      (is (some #(= :position-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store false)
        v (governor/check {:client-id "nobody"} {} (onboard all-fields false) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store false)
        v (governor/check req {} (assoc (onboard all-fields false) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-provisional-start
  (let [st (fresh-store false)
        v (governor/check req {} {:op :approve-provisional-start :effect :propose
                                  :position-id "P-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store false)
        v (governor/check req {} (assoc (onboard all-fields false) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
