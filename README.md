# cloud-itonami-isco-4416

Open Business Blueprint for **ISCO-08 4416**: Personnel Clerks — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — PersonnelClerksAdvisor ⊣
PersonnelClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
14 tests / 28 assertions green.

The onboarding HARD invariants — set coverage and a policy gate, not
paperwork speed:

1. **Field completeness** — the proposed submitted-fields set must be
   a superset of the position's registered required-fields set (an
   incomplete personnel file cannot be onboarded).
2. **Background-check gate** — when the position registers a
   background check as required, onboarding without a cleared check
   is a policy violation.

Also HARD: unregistered/foreign position, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:approve-provisional-start` (starting work before all checks
complete, under exception), low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
