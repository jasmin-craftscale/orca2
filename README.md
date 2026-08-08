# ORCA 2.0

Gate automation for logistics facilities — container terminals and distribution
centres, where a truck arrives at a lane, a camera reads its plate, and the process
the site's own administrators designed runs against the customer's systems until the
barrier lifts. A ground-up rewrite of a system in production today; **this repository
is the new build** (Java 25 · Spring Boot 4 · SQL Server · Flowable).

Two laws shape everything here: **the gate must keep working when other things do
not**, and **the site's processes belong to the site**.

## Start here

| You want to | Read |
|---|---|
| Run it locally — stack, services, one truck through the gate | **[`docs/deployment.md`](docs/deployment.md)** |
| Understand the repository layout and how it builds | [`docs/REPOSITORY_GUIDE.md`](docs/REPOSITORY_GUIDE.md) |
| Understand the target design and what it guarantees | [`docs/ORCA_ARCHITECTURE.md`](docs/ORCA_ARCHITECTURE.md) — §B10 first |
| Know what is deliberately undecided | [`docs/ORCA_OPEN_QUESTIONS_REGISTER.md`](docs/ORCA_OPEN_QUESTIONS_REGISTER.md) |
| See what has been built and verified so far | `docs/phase-0-report.md` · `docs/phase-1-report.md` · `docs/phase-1-hardening-report.md` |
| Build something | The current plan (`docs/phase-2-plan.md`) and its reference sheet |

## The rules apply to humans too

[`AGENTS.md`](AGENTS.md) is written for AI coding agents, but every rule in it binds
people equally — the hard rules are enforced by **ten build checks that fail the
build**, not by review vigilance. Read it once; it is short on purpose. The
definition of done for any substantive change: build green **including**
`check` and `integrationTest`, a property test for every guarantee touched, and a
short written report — what was built, what could not be, every decision the
specification did not dictate, anything that looked wrong (reported, not silently
corrected).

**If something is unspecified, check the register before concluding it was
forgotten. Never invent a resolution — a gap reported is worth more than a gap
filled.**

## Production status — read before shipping anything

⚠️ **This repository is not yet installable at a customer site, and `deploy/` is a
development stack, not a production artifact.** The gate path itself is built and
verified end to end; what stands between it and a customer machine (release images,
licensing, secrets provisioning, reverse proxy, the operator console, two vendor
answers) is known, named, and tracked — see the production half of
[`docs/deployment.md`](docs/deployment.md) and the register. The deployment phase is
the next implementation plan after Phase 2.
