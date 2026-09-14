# Execution plans — historical archive

Both plan packages in this directory are **completed historical records**,
not active roadmaps:

- `dev-factory-v1/` — the original Dev Factory v1 plan (M0/M1 accepted;
  later scope superseded).
- `dev-factory-v1-replanned-handoff/` — the `pi-minimal-v2` replanned
  handoff; its `STATUS.yaml` records all milestones complete.

Their prompts, rules, milestones, reports, and evidence were instructions
for past execution sessions. Do not execute, resume, or extend them, and do
not treat them as architecture or implementation guidance.

The files are kept byte-identical on purpose: the replanned package carries
a `MANIFEST.sha256`, and `scripts/factory` references evidence paths in this
tree. Add new markers at this directory level only — never edit inside the
packages.
