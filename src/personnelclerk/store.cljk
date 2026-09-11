(ns personnelclerk.store
  "SSoT for the ISCO-08 4416 community personnel clerks actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client   — a registered organization (:client-id, :name)
    position — a registered position {:position-id :client-id :name
               :required-fields #{field-str}
               :background-check-required? bool}.
               `:required-fields` is the registered set a proposed
               onboarding's submitted fields must fully cover (an
               incomplete personnel file cannot be onboarded, missing
               fields are not clerical convenience);
               `:background-check-required?` gates whether onboarding
               requires a cleared background check (onboarding
               without clearance is a policy violation, not
               paperwork speed).
    record   — a committed operating record (approved onboarding) —
               written ONLY via commit-record!.
    ledger   — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (position [s position-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-position! [s p])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (position [_ position-id] (get-in @a [:positions position-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-position! [s p]
    (swap! a assoc-in [:positions (:position-id p)] p) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :positions {} :records [] :ledger []}
                                   seed)))))
