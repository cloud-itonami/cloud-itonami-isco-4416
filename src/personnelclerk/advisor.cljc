(ns personnelclerk.advisor
  "PersonnelClerksAdvisor — proposes an onboarding operation (approve
  an onboarding, approve a provisional start) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `personnelclerk.governor` checks field completeness and the
  background-check gate independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-onboarding|:approve-provisional-start
               :effect :propose :position-id str
               :submitted-fields #{str} :background-check-cleared bool
               :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake position-id submitted-fields
                             background-check-cleared] :as request}]
  {:op op
   :effect :propose
   :position-id position-id
   :submitted-fields submitted-fields
   :background-check-cleared background-check-cleared
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a personnel clerk advisor. Given a request, propose an
   :op, the :position-id, :submitted-fields and
   :background-check-cleared, an honest :confidence and a :stake.
   Never call an incomplete personnel file or an uncleared background
   check conforming — the governor checks both against the registered
   position record.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
