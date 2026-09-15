# Advisory semantic critique — Developer Flow M3 experiment

Date: 2026-09-15. Model: `glm-5.3-flash` (same model as the coding runtime), temperature 0.

## Question

Does an independent model review of a frozen candidate find semantic problems that the
task's verification plan does not find, without blocking good candidates?

## Method

- `critique.py` opens one fresh model context per candidate. The reviewer sees only the task
  text and the exact frozen `candidate.patch`. It never sees ground-truth solutions, run logs,
  or the coding session.
- It returns classified findings (`REPAIRABLE_DEFECT`, `DECISION_REQUIRED`, `NOTE`, with a
  blocking flag and confidence). Results are stored in `results/` only. Nothing in Factory reads
  them, and no routing or repair depends on them.
- Findings were graded by hand against the ground-truth rubrics in `evals/solutions/`
  (grading only; the reviewer never saw them).

## Candidates and results

| Candidate | Kind | Verification | Critique verdict | Blocking findings | Grade |
|---|---|---|---|---|---|
| MODSIDECAR-208 `8421d5ca` (M1 run) | real, accepted | PASS | ACCEPT | 0 (4 notes) | correct accept; notes were accurate but added no value |
| MGRENTITLE-192 `2839078a` (M1 run) | real, accepted | PASS | ACCEPT | 0 (2 notes) | correct accept; both notes accurate |
| MGRENTITLE-192 seeded: `moduleId` without version, test changed to agree | seeded control | would pass `mvn test` (test changed with the defect) | REPAIR | 2 (HIGH) + 1 non-blocking | hit: named the exact AC1 violation and the test that hides it; 1 LOW note was noise |
| MODSIDECAR-207 M3 runs | real, after human decision | attempts 1–3 never reached verification (40-attempt budget, gateway DNS failure); attempt 4 `640c7f50` (budget 80) PASS | not run in this experiment | — | — |

Tally over real accepted candidates: 2 reviewed, 0 false blockers, 0 real escaped defects
available to catch. Seeded control: 1 of 1 caught. Useless or noisy findings: 7 non-blocking
notes restating that criteria were met, and 1 low-confidence speculation. Cost: about 6.5k to
7.2k tokens and under one minute per review.

## Recommendation

**Keep advisory; do not promote to a routing-relevant reviewer.**

- There were no false blockers, and the reviewer found a defect that a self-consistent test change
  hides. That is the kind of problem this review is meant to catch.
- The evidence is too small to justify a gate: two real candidates, both correct, and one seeded
  defect. No real escaped semantic defect has been observed yet on this benchmark, so the review
  has not yet shown accepted-code value on real runs.
- Next evidence to collect before promotion: run it (advisory) on every accepted candidate of
  future real runs and count real hits and false blockers. Do not tune the prompt for these three
  cases.
