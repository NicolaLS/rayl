# QA

Test management and testware for Rayl. Linked Specs (`specs/`) describe what the product
*is*. This directory holds how we *test* it: plans, risks, checklists, session notes and
reports. Records here may cite spec IDs and risk IDs, but they are not Linked Specs records.

| Artifact | Purpose |
|---|---|
| [test-plan.md](test-plan.md) | Master test plan: scope, strategy, levels, environments, criteria, roadmap, decisions |
| [product-risks.md](product-risks.md) | Product and project risk register (`PR-xx`, `PJ-xx`) that drives test depth |
| [releases/1.1/](releases/1.1/) | 1.1: [release test plan](releases/1.1/release-test-plan.md) (scope cut and schedule), [status](releases/1.1/status.md) (work items, risk coverage, defects), [test log](releases/1.1/test-log.md) (executions), [wallet lifecycle traceability](releases/1.1/lifecycle-traceability.md), [test summary report](releases/1.1/test-summary-report.md) |
| [checklists/](checklists/) | [Release regression checklist](checklists/release-regression.md) (App Live, per RC) and [mainnet acceptance protocol](checklists/mainnet-acceptance.md) (real money, per RC) |
| [stub-assumptions.md](stub-assumptions.md) | What the host tests' stubs assume about real systems, and how each assumption is verified |

## Where things live

| Kind | Where | Rule |
|---|---|---|
| Living documents: test plan, risk register, stub assumptions, checklists | `qa/` | Edited in place, reviewed like code |
| Per release: plan, executions, summary report | `qa/releases/<version>/` | Frozen when the release ships; the repo tag is the snapshot. Checklist and mainnet runs go in `runs/` there, one dated file per run |
| Automated tests | beside the code | Results come from CI, not from documents |
| Defects and their status | Linear | Never copied into documents; link a Linear view instead |
| Legal and store texts | `distribution/` | Owned by the product owner, reviewed by QA |
| What the product is | `specs/` | Linked Specs only |

When manual runs outgrow markdown (many cases or several testers), move them to a test
management tool; keep strategy and risks here.

Planned additions (created when the work starts):
- `charters/`: exploratory test charters
- `sessions/`: exploratory session notes
- `reports/`: a test summary report per release candidate

Conventions:
- IDs are stable. Legend: `PR-` product risk, `PJ-` project risk, `O-` observation,
  `D-` decision, `SI-` specification issue, `A` stub assumption (`stub-assumptions.md`),
  `C1–C8` audit criteria, `P<phase>.<n>` roadmap item, `MOB-n` Linear issue.
  In a release plan: `M1–M12` Must, `SH1–SH6` Should, `I1–I3` integration items.
  Defect severity is `S1–S4`. Mainnet protocol steps are `MN-n`. Checklist rows (`B U R P E I L`)
  and the lifecycle rules (`L`, `P`) are numbered within their own file.
- Automated tests live beside the code they test, not here.
- Tests cite an ID only when it explains *why* the case matters.
