# ORCA — BPMN execution profile

**The conventions the ORCA builder's compiler emits, and the runtime honours.**
Durable: this document outlives any phase, and it is the contract between the
builder developer and `orca-runtime`.

**Status: PROPOSED.** Written by the Phase 1 build session from the runtime side.
It is what the engine and the delegates actually do today — every statement below
is executed by `GateVisitProcessIT`, not aspirational — but the *builder* half has
not been reviewed by the developer building it. Anything here they disagree with
is a conversation, not a defect.

---

## 1 · Why there is a profile at all

ADR-006 keeps the ORCA builder and compiles its designs to BPMN 2.0 at publish.
That makes the compiler permanent runtime infrastructure (register item **S3**
records the cost deliberately), and it makes the BPMN dialect a **contract**
rather than an implementation detail: the compiler emits it, the engine executes
it, and a conformance harness has to prove the two agree.

The narrower the dialect, the smaller that harness is and the fewer engine
behaviours the compiler has to model. So this profile is deliberately small, and
the test for adding to it is *"can an administrator express something today that
the profile cannot carry?"* — not *"is this construct available?"*

**The first fixture is
`services/orca-runtime/src/main/resources/processes/gate-visit.bpmn20.xml`.** It
is written as if it were compiler output: nothing in it is something a person
would write by hand that a compiler could not reproduce.

---

## 2 · Supported constructs

| Construct | Supported | Notes |
|---|---|---|
| `startEvent` (none) | ✅ | One per process. The visit is already admitted when it fires |
| `endEvent` (none) | ✅ | Several permitted; **name them**, see §5 |
| `serviceTask` | ✅ | Bound by `flowable:delegateExpression`, §3. Always `flowable:async="true"`, §4 |
| `exclusiveGateway` | ✅ | With a `default` flow. See §5 |
| `sequenceFlow` + `conditionExpression` | ✅ | Conditions read **branch discriminators only**, §6 |
| `boundaryEvent` + `errorEventDefinition` | ✅ | The failure branch. §7 |
| `boundaryEvent` + `timerEventDefinition` | ⚠️ **on service tasks: no** | It cannot fire. §8 — read this one |
| `userTask` | ⏳ Phase 2 | Work items are `workitem`'s, and that module is empty today |
| `subProcess`, `callActivity` | ⏳ | Subflows are a builder concept (§C1); the mapping is not settled |
| `multiInstance` | ⏳ | Map-iterator children exist in the data model (`parent_execution_id`) and the admission index already accommodates them, but nothing emits them yet |
| Anything else | ❌ | Not "forbidden" — **unspecified**, which means the conformance harness does not cover it and the runtime has never run it |

---

## 3 · Delegate binding — the bean names are an API

A service task binds to code with:

```xml
<serviceTask id="..." flowable:delegateExpression="${connectorCallDelegate}" flowable:async="true"/>
```

**Two bean names exist, and they do not move.**

| Expression | What it does | Class |
|---|---|---|
| `${connectorCallDelegate}` | Calls a configured customer system | `execution.domain.ConnectorCallDelegate` |
| `${deviceCommandDelegate}` | Issues one device command | `execution.domain.DeviceCommandDelegate` |

⚠️ **Generic, never per-connector.** `${acmeTosDelegate}` would be a bean name the
compiler has to *invent* at compile time — and a compiler that invents bean names
can emit a process that nothing can run. One delegate, parameterised by variables.

Renaming one of these breaks every process already published, including ones
running at a customer site. They are declared in
`execution.ExecutionConfiguration` with that sentence next to them.

`flowable:delegateExpression` is the one Flowable-namespaced attribute in the
profile. BPMN 2.0 has no vendor-neutral way to bind a service task to code, so a
compiler emitting for *any* engine emits something equivalent; this is the seam
where a second engine would need one line changed per task, not a rewrite.

---

## 4 · Every service task is `flowable:async="true"`

Not a tuning choice — an architectural requirement.

§B9: *"the lane lock is released at commit — no outbound call happens while
holding it."* A synchronous service task runs inside the transaction that started
the process instance, which is **admission's** transaction, with the lane row
still locked. A slow customer system would then hold a lane shut for exactly as
long as it took to answer.

`async="true"` commits the instance first and runs the task on a worker. Proven by
`GateVisitProcessIT.noOutboundCallHappensInsideTheStartingTransaction`: immediately
after the start call returns, the connector has not been invoked.

**Consequence for deployment:** the async executor must be running. A runtime
instance with `flowable.async-executor-activate=false` accepts visits and advances
none of them.

---

## 5 · Gateways and end states

- An `exclusiveGateway` **must** declare a `default` flow. A response nobody wrote
  a branch for goes to a human, never to an implicit success.
- **Name your end events.** The runtime and the console distinguish outcomes by
  end-event id, so `visitReleased` and `manualHandlingRequired` mean different
  things to an operator. An unnamed end event is an outcome nobody can report on.
- `manualHandlingRequired` is a **named end state, not an error**. A visit that
  needed a human is not a visit that crashed, and the two must stay
  distinguishable — the clerk workflow that receives it is Phase 2.

---

## 6 · Variables carry correlation keys and branch discriminators — nothing else

§C2: *"process variables carry correlation keys only"*, so that the engine's
history tables stay bounded and business payloads stay inside the platform's own
retention model.

**This profile refines that by one category, and says so rather than assuming it:**
a gateway has to branch on *something*, so a **branch discriminator** — a short,
enumerable status token — is permitted. A response body is not, however small it
looks today.

The names are `execution.domain.ProcessVariables`, and they are as much an API as
the bean names:

| Variable | Kind | Meaning |
|---|---|---|
| `visitExternalId` | correlation key | The visit. Everything else is looked up by it |
| `laneExternalId` | correlation key | The lane, in core's published vocabulary |
| `connectorName` | correlation key | Which configured connector — a **name**, never an endpoint |
| `commandAction` | correlation key | `RAISE_GATE`, `LOWER_GATE`, … (§C3) |
| `commandDeadlineMillis` | correlation key | After which the outcome is `UNKNOWN` |
| `connectorOutcome` | discriminator | What the customer system said, as a routing token |
| `deviceCommandOutcome` | discriminator | `EXECUTED` · `FAILED` · `UNKNOWN` |

⚠️ **`connectorName` is a name, not a URL.** The endpoint, its authentication mode
and its certificate trust are configuration owned by `runtime.integration` (§C2). A
process carrying a URL would have to be republished to change one.

---

## 7 · Errors, and the two device codes

A delegate that cannot complete raises a **BPMN error**, caught by a boundary
event on its own task. It does not throw a runtime exception: a runtime exception
rolls the job back and the async executor retries it, which for a customer system
that is down means retrying into a wall and ending as a dead-letter job nobody at
the gate ever sees.

| Code | Raised when |
|---|---|
| `connector.failed` | The connector call could not be made or produced no usable answer |
| `device.command.failed` | The device host rejected the command |
| `device.state.unknown` | **The deadline passed with no answer** |

⚠️ **`device.state.unknown` is deliberately a different code from
`device.command.failed`.** §B10: an unknown outcome is resolved by *verifying the
device's actual state*, never by retrying and never by assuming failure. A process
cannot route those two differently if they arrive as one code — and a compiler
that emitted one boundary event for both would remove the distinction the whole
command design exists to preserve.

**Every device-output step needs a failure branch**, and §C1's publish-time
validation is where that becomes enforceable rather than conventional.

---

## 8 · ⚠️ A boundary timer on a service task cannot fire

**This is an observed engine behaviour, not an opinion.** The Phase 1 plan asked
for "error boundary + timer on each service task". `gate-visit` ships the error
boundary and **does not ship the timer**, and here is the evidence.

`GateVisitProcessIT.doesABoundaryTimerOnAServiceTaskEverFire` runs a one-second
boundary timer on a service task whose delegate blocks for four seconds. The
instance ends at `completed`. The timer never fires.

The reason: a boundary timer is created when its activity is *entered* and removed
when the activity *completes*. A service task's delegate runs to completion inside
that same transaction, so the timer job is written and deleted before any other
thread can see it. `flowable:async="true"` does not change this — it moves the
whole activity onto a worker thread, transaction and all.

**A timer that cannot fire is worse than no timer**, because it looks like
protection. So:

- The deadline on an **outbound call** lives where §B8 puts it — *on the call*.
  `commandDeadlineMillis` is enforced by the device-command transport, not by the
  engine.
- A timer that genuinely guards a step needs the step to be a **wait state**.
  `flowable:triggerable="true"` makes a service task one: the delegate starts the
  work, the instance parks, and something triggers it later. A boundary timer on
  *that* does fire.

**Open, and for the builder developer and the product owner together:** whether
device commands become triggerable tasks. It is the shape §B9's *"commits the step
as command-issued and releases every lock"* actually describes, and it is what
would let the engine — rather than a transport — own the deadline. It is a design
point the Phase 1 plan did not settle, and Phase 1 did not settle it either.

---

## 9 · Deployment: classpath auto-deploy is scaffolding

Today `gate-visit` is auto-deployed from
`src/main/resources/processes/*.bpmn20.xml` at startup. **That is Phase 1
scaffolding and it is not the product path.**

The product path is the publish pipeline in §C1: validate → compile to BPMN →
freeze an immutable version → push to `POST /internal/deployments/v1`, idempotent
by deployment id. It arrives with builder integration.

Two things that already hold and must keep holding:

- **The process definition key is stable** (`gate-visit`). A deployment assigns a
  version *by key* to lanes and areas.
- **A running visit finishes on the version it started** (§B10, register #24 —
  snapshot at publish). Publishing does not disturb anything in flight.

---

## 10 · The conformance harness

Register item **#11** and **U6** both land here: the comparison harness is *"still
the only way to prove a compiled process behaves as designed"*, and it survives the
death of the migration tooling as **test infrastructure**.

What it needs, in the order it needs it:

1. **Fixtures** — an authored ORCA design, the BPMN the compiler produced, and the
   expected execution trace. `gate-visit.bpmn20.xml` is fixture #1, and it exists
   before the compiler does on purpose: it is the target, written by the side that
   has to run it.
2. **A trace comparison**, not an XML comparison. Two different BPMN documents can
   be the same process; asserting on the text would make every compiler
   optimisation a failing test.
3. **A profile-conformance check** — that the compiler emits nothing outside §2.
   That check is the one that keeps this document true, and without it the profile
   decays into a description of what the compiler happened to do.

---

*Companion documents: `docs/ORCA_ARCHITECTURE.md` §B9, §B10 and §C2 ·
`docs/ORCA_OPEN_QUESTIONS_REGISTER.md` items #11, S3, U6 ·
`docs/phase-1-report.md` for what Phase 1 built against this profile and what it
left open.*
