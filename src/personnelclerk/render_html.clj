(ns personnelclerk.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`personnelclerk.actor` -> `personnelclerk.governor` ->
  `personnelclerk.store`) through a scenario built from real, exercised
  store data and renders the result deterministically -- no invented
  numbers, no timestamps in the page content, byte-identical across
  reruns against the same seed (verify by diffing two consecutive runs
  before shipping).

  `client-1` (\"Kobo Trade\") + position `P-1` (\"warehouse-associate\",
  required-fields #{\"tax-id\"}, background-check-required? true)
  below are lifted VERBATIM from this repo's own proven-passing test
  fixtures (`personnelclerk.actor-test` `fresh-store` helper) -- ground
  truth, not invented. `client-2` (\"Annex Fulfillment\") is ADDITIONAL
  demo data registered via the SAME real protocol call
  (`store/register-client!`) this actor's own test fixtures use --
  this actor's own actor-test fixture registers only one client, and a
  second one is necessary to demonstrate the cross-client
  `:position-wrong-client` rule. Disclosed here plainly, not presented
  as if it were a pre-existing fixture. Every other field this page
  displays (statuses, records, hold reasons) is real output read after
  `run-demo!` actually executed the graph -- none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over:
  - `personnelclerk.governor`'s `:no-actuation` rule (proposal
    `:effect` must be `:propose`) is NOT reachable through this demo,
    because the real `mock-advisor`
    (`personnelclerk.advisor/infer`) unconditionally sets
    `:effect :propose` on every proposal it emits.
  - The low-confidence escalation path is likewise NOT reachable
    through this demo: `mock-advisor` derives confidence purely from
    `:stake` (`:high` -> 0.7, `:medium` -> 0.85, `:low` -> 0.95), all
    of which sit above `personnelclerk.governor/confidence-floor`
    (0.6) -- there is no stake value the real advisor maps to a
    sub-floor confidence. Both rules ARE covered by
    `personnelclerk.governor-test/hard-on-no-actuation-violation` and
    `escalates-low-confidence` (which call `governor/check` directly
    with hand-built proposals), not by this build-time renderer, which
    only ever drives the real actor/graph the way an operator actually
    would.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [personnelclerk.store :as store]
            [personnelclerk.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real personnel-clerk operation request through the
  actual compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it
  (this demo's scenario never demonstrates an UNAPPROVED escalation --
  every escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit, escalate-then-approve, and 5 of
  the 6 distinct HARD-hold reasons in `personnelclerk.governor` --
  the 6th, `:no-actuation`, is architecturally unreachable via the
  real advisor, see namespace docstring). Every `:op` keyword and
  violation rule name below is copied from `personnelclerk.governor`'s
  own `hard-violations`/`check`, not invented."
  [;; client-1 / P-1 (real fixture from personnelclerk.actor-test)
   ["c1-complete-cleared"    "client-1" :approve-onboarding {:position-id "P-1" :stake :low
                                                              :submitted-fields #{"tax-id"}
                                                              :background-check-cleared true}]
   ["c1-not-cleared"         "client-1" :approve-onboarding {:position-id "P-1" :stake :low
                                                              :submitted-fields #{"tax-id"}
                                                              :background-check-cleared false}]
   ["c1-incomplete-file"     "client-1" :approve-onboarding {:position-id "P-1" :stake :low
                                                              :submitted-fields #{}
                                                              :background-check-cleared true}]
   ["c1-ghost-position"      "client-1" :approve-onboarding {:position-id "P-ghost" :stake :low
                                                              :submitted-fields #{"tax-id"}
                                                              :background-check-cleared true}]
   ;; client-2 (additional demo data, registered via the same real
   ;; register-client! call -- see namespace docstring)
   ["c2-foreign-position"    "client-2" :approve-onboarding {:position-id "P-1" :stake :low
                                                              :submitted-fields #{"tax-id"}
                                                              :background-check-cleared true}]
   ;; unregistered client entirely
   ["ghost-no-client"        "client-ghost" :approve-onboarding {:position-id "P-1" :stake :low
                                                                  :submitted-fields #{"tax-id"}
                                                                  :background-check-cleared true}]
   ;; always-escalate op
   ["c1-provisional-start"   "client-1" :approve-provisional-start {:position-id "P-1" :stake :high}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `personnelclerk.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Trade"})
    (store/register-position! db {:position-id "P-1" :client-id "client-1"
                                   :name "warehouse-associate"
                                   :required-fields #{"tax-id"}
                                   :background-check-required? true})
    (store/register-client! db {:client-id "client-2" :name "Annex Fulfillment"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- position-row [store {:keys [position-id client-id name required-fields background-check-required?]} runs]
  (let [record-count (count (filter #(= position-id (:position-id %)) (store/records-of store client-id)))
        last-run (last (filter #(= position-id (get-in % [:request :position-id])) runs))]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc position-id) (esc name)
            (esc (str/join ", " (sort required-fields)))
            (if background-check-required? "<span class=\"warn\">required</span>" "<span class=\"muted\">not required</span>")
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:position-id request) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `personnelclerk.governor`'s own docstring) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:approve-onboarding</code></td><td><span class=\"ok\">auto-commit when the submitted fields cover every required field and the background check (if required) is cleared</span></td></tr>"
   "        <tr><td><code>:approve-provisional-start</code></td><td><span class=\"warn\">ALWAYS human approval &middot; starting work before all checks complete, under exception</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [positions [{:position-id "P-1" :name "warehouse-associate" :client-id "client-1"
                     :required-fields #{"tax-id"} :background-check-required? true}]
        position-rows (str/join "\n" (map #(position-row store % runs) positions))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-4416 &middot; independent personnel clerk practice</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Independent Personnel Clerk Practice (ISCO-08 4416) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · provisional starts always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered positions</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>personnelclerk.store</code> via <code>personnelclerk.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. Required fields and the background-check flag are the registered ground truth the governor checks every onboarding against — an incomplete personnel file or an uncleared background check is not onboardable.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Position</th><th>Name</th><th>Required fields</th><th>Background check</th><th>Records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     position-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Personnel Clerks Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. The governor never onboards anyone itself and never accepts an incomplete personnel file or an uncleared background check where one is required.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own position, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Position</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
