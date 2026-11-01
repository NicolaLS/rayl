# QA

Test management and testware for Rayl. Linked Specs (`specs/`) describe what the product
*is*. This directory holds how we *test* it: plans, risks, checklists, session notes and
reports. Records here may cite spec IDs and risk IDs, but they are not Linked Specs records.

| Artifact | Purpose |
|---|---|
| [test-plan.md](test-plan.md) | Master test plan: scope, strategy, levels, environments, criteria, roadmap, decisions |
| [product-risks.md](product-risks.md) | Product and project risk register (`PR-xx`, `PJ-xx`) that drives test depth |

Planned additions (created when the work starts):
- `checklists/`: the release regression checklist
- `charters/`: exploratory test charters
- `sessions/`: exploratory session notes
- `reports/`: a test summary report per release candidate

Conventions:
- IDs are stable: `PR-` product risk, `PJ-` project risk, `O-` observation, `D-` decision,
  `P<phase>.<n>` roadmap item.
- Automated tests live beside the code they test, not here.
- Tests cite an ID only when it explains *why* the case matters.
