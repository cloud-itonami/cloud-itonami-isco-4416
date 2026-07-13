(ns personnelclerk.governor
  "PersonnelClerksGovernor — the independent safety/traceability layer
  for the ISCO-08 4416 community personnel clerks actor (itonami
  actor pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled
  on cloud-itonami-isco-4311's bookkeeping.governor. Onboarding
  twist: a proposed onboarding's submitted-fields set must fully cover
  the registered required-fields set — an incomplete personnel file
  cannot be onboarded, missing fields are not clerical convenience —
  and if the position registers a background check as required, an
  onboarding without a cleared check is a policy violation, not
  paperwork speed.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. position basis       — an onboarding approval must cite a
                           REGISTERED position belonging to this
                           client.
    4. field completeness  — the proposed submitted-fields set must
                           be a superset of the position's registered
                           :required-fields set (no partial personnel
                           file).
    5. background-check gate — if the position's registered
                           :background-check-required? is true, the
                           proposed background-check-cleared must be
                           true.
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :approve-provisional-start (starting work before all
                           checks complete, under exception).
    7. low confidence (< `confidence-floor`)."
  (:require [clojure.set :as set]
            [personnelclerk.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record p]
  (let [{:keys [op submitted-fields background-check-cleared]} proposal
        approve? (= :approve-onboarding op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and approve? (nil? p))
      (conj {:rule :unknown-position :detail "未登録 position への入社承認は不可"})

      (and approve? p (not= (:client-id p) (:client-id request)))
      (conj {:rule :position-wrong-client :detail "position が別 client のもの"})

      (and approve? p
           (not (set/superset? (set submitted-fields) (:required-fields p))))
      (conj {:rule :incomplete-personnel-file
             :detail (str "未提出必須項目 "
                          (vec (set/difference (:required-fields p) (set submitted-fields)))
                          "（不完全な人事ファイルは入社処理できない）")})

      (and approve? p (:background-check-required? p) (not (true? background-check-cleared)))
      (conj {:rule :background-check-not-cleared
             :detail "身元調査未クリアの入社は許可されない（クリアランス無しの入社は事務処理の速さではなく方針違反）"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `personnelclerk.store/Store`. Pure — never
  mutates the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        p (some->> (:position-id proposal) (store/position store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record p)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :approve-provisional-start (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
