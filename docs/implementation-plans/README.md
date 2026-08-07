# Implementation plans

**Everything in this folder is a work artifact with a shelf life.** One plan file
per phase, its report beside it when the phase lands, and nothing else.

| File | What it is |
|---|---|
| `phase-<n>.md` | The implementation plan: outcome, gates, work packages, the verification table ("run these, do not assume them"), and what the final report must contain |
| `phase-<n>-report.md` | The build session's report against that plan — review it **before** the code |

## The rules

- **A build session is started with exactly one instruction:** *"Read
  `docs/implementation-plans/phase-<n>.md` and begin."* The plan is the session's
  entire authority; if something is not in it or in the durable docs, the builder
  reports the gap rather than inventing a resolution.
- **Delete a phase's files when their review value is exhausted.** Git history
  keeps them forever; the working tree does not need to.
- **Promotion before deletion.** Anything a phase produces that must outlive it —
  a decision, a convention, a contract (e.g. the BPMN execution profile) — is
  promoted into the durable docs (`docs/*.md`, the open-questions register, or the
  code) *before* the phase folder is pruned. If it only exists here, it dies here.
- **Nothing durable lives here.** The architecture, the register, the primitives
  guide and the repository guide stay in `docs/` proper.

Phase 0 predates this convention, which is why it has three files
(`phase-0-kickoff.md` + `phase-0-brief.md` + `phase-0-report.md`) instead of one
plan: the kickoff/brief split existed only because that session was bootstrapped
cold from the repository root. From Phase 1 on: one plan file per phase.
