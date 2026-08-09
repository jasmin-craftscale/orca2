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
| `boundaryEvent` + `timerEventDefinition` | ⚠️ **on service tasks: no** · ✅ on `userTask` | On a service task it cannot fire (§8). On a wait state it fires — §8a |
| `userTask` | ✅ Phase 3 | The manual-input wait state. **Bare — no attributes, no listeners.** §8a |
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
- `manualHandlingResolved` is a **named end state, not an error**. A visit that
  needed a human is not a visit that crashed, and the two must stay
  distinguishable. ⚠️ *Renamed in Phase 3:* the Phase 1 fixture ended at
  `manualHandlingRequired` because the clerk workflow did not exist; the human
  branch is now a **wait state** (§8a) and the end event after it records that a
  person resolved it.

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
| `commandDeviceExternalId` | correlation key | Which device on the lane. **Required** — see below |
| `commandDeadlineMillis` | correlation key | After which the outcome is `UNKNOWN` |
| `connectorOutcome` | discriminator | What the customer system said, as a routing token |
| `deviceCommandOutcome` | discriminator | `EXECUTED` · `FAILED` · `UNKNOWN` |

⚠️ **`connectorName` is a name, not a URL.** The endpoint, its authentication mode
and its certificate trust are configuration owned by `runtime.integration` (§C2). A
process carrying a URL would have to be republished to change one.

⚠️ **`commandDeviceExternalId` is required on every device step, and a compiler
that omits it emits a process that cannot command anything.** Added by H1, with a
reason that is not a convention: the real device-host contract
(`docs/device-host-outbound-from-1x.md`, DERIVED-FROM-1X) addresses the device **in
the URL path** — `POST /api/{device}/raiseGate` — so the device's external id *is*
the host's address. It was optional under the invented contract this repository
shipped in WP7, which carried the device in a JSON body. A lane with exactly one
barrier still has to name it.

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

## 8a · The manual-input wait state — `userTask`, bare

**Added in Phase 3, executed by `WorkItemLifecycleIT`.** A step a person must
finish compiles to a plain `userTask`:

```xml
<userTask id="manualInput" name="manual handling"/>
```

**Emit nothing else on it.** No `flowable:assignee`, no `flowable:candidateGroups`,
no form key, no task listeners. Assignment, routing and eligibility live in the
platform's work item, not in the engine's identity tables — and every attribute a
compiler emits is a binding a designer could break.

**Work-item creation is platform behaviour, not process design.** An engine event
listener (`WorkItemCreationListener`, registered once at startup) notices every
task creation and writes the work item **in the same transaction that parks the
engine**. The compiler emits no binding for this, for the same reason visit
completion is a listener rather than a mandatory final service task: a designer
who could omit it would produce a process that parks forever with no item in any
queue. The listener is `isFailOnException = true`, so the item and the wait state
commit together or roll back together — proven by fault injection.

**The task's id is the node reference.** The screen identity (core's
`topology.screen`, Phase 3 WP2) points at the `userTask`'s id; the compiler must
keep those ids stable across republications of the same design, or every routing
rule and screen binding breaks (the same discipline as the process definition
key in §9).

**Completion comes from the platform, never from the console directly.** The
work item's `complete` presents the engine task id back inside one transaction —
the item's guarded update, the engine's advance, and whatever the process then
runs synchronously (for `gate-visit`, the visit's own closing write) are one
commit. A submit the engine is not waiting on is refused whole
(`WORK_ITEM_OUT_OF_ORDER`).

**A boundary timer on this task fires.** Unlike §8's service-task case, the wait
state genuinely parks: the timer job is committed and visible to the async
executor while the task waits. §8b gives the SLA construct.

---

## 8b · The SLA timer — a non-interrupting boundary timer on the wait state

**Added in Phase 3, executed by `WorkItemSlaIT`: the timer fires, survives a
restart firing exactly once, and never fires falsely.** A manual step with a
time target compiles to:

```xml
<boundaryEvent id="manualInputSla" attachedToRef="manualInput" cancelActivity="false">
  <timerEventDefinition>
    <timeDuration>${workItemSla.breachDuration(execution, 'manualInput')}</timeDuration>
  </timerEventDefinition>
</boundaryEvent>
<sequenceFlow id="slaToRecord" sourceRef="manualInputSla" targetRef="recordSlaBreach"/>
<serviceTask id="recordSlaBreach" flowable:delegateExpression="${workItemSlaBreachDelegate}"
             flowable:async="true"/>
<sequenceFlow id="recordToNoted" sourceRef="recordSlaBreach" targetRef="slaBreachRecorded"/>
<endEvent id="slaBreachRecorded" name="SLA breach recorded"/>
```

Rules the compiler must keep, and why:

- **`cancelActivity="false"`, always.** A breach marks the item; it must not kill
  the operator's work. An interrupting timer here would delete the task an
  operator may be mid-way through.
- **Two more stable bean names**: `workItemSla` (the duration source) and
  `workItemSlaBreachDelegate` (the recording step). Like the two delegates, they
  are link targets and do not move.
- **The node reference travels as a string literal** — the compiler knows the
  task id it is attaching the timer to. No engine introspection, no naming
  convention on boundary-event ids.
- **The timer is armed even when no threshold is configured** — a BPMN boundary
  event is static, and an expression returning null fails the task's entry.
  `workItemSla` answers a ten-year sentinel for an unconfigured step; the job
  costs one engine-table row and is deleted with the task. The alternative
  (emit the timer only when a threshold exists at compile time) would freeze
  threshold configuration into the published process.
- **Thresholds are resolved at arming time** — the screen identity's `max_sec`,
  else the `MAX_PROCESSING_TIME_SEC` setting. A threshold changed later applies
  to the next item, not to one already parked. Snapshot behaviour, stated.
- **The breach branch's end event concludes the branch, not the process.** The
  instance keeps waiting at the task. This is why the platform closes visits on
  `PROCESS_COMPLETED` (with the end-event id stashed from `ACTIVITY_COMPLETED`
  in the same command) rather than on any end event — Phase 1's
  any-end-event-closes rule broke the day the process gained a non-interrupting
  branch, and `VisitCompletionListener` records the measurement.

**For the builder developer's open §8 question:** this settles the wait-state
half from the running side — timers on wait states are real, restart-safe
protection. The service-task half (triggerable device commands) remains open,
and remains yours with the product owner.

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
