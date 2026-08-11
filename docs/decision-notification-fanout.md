# Decision brief — cross-instance notification fan-out

**For the product owner · 10 August 2026 · Decision required before stream 2 WP4**

**What is blocked:** only the fan-out half of stream 2's final work package. Read
models, durable notifications and the authenticated WebSocket-ticket surface can
proceed first.

## The decision

A browser is connected to runtime instance A. A visit, timer or work-item change is
processed by instance B. How does B tell A to prompt that browser, when the site is
multi-instance and deliberately has no message broker?

The answer does not need broker-grade delivery. The architecture has already ruled
that WebSocket push is **best effort** and that the underlying query is authoritative
on connect and reconnect. The decision is therefore about a cheap cross-instance
invalidation hint, not a second durable messaging system.

This brief recommends an answer. It does not authorise implementation.

## What has been verified

- Every deployment profile may run more than one instance. ADR-015 forbids assuming
  one process; ADR-007 declines a broker inside the site.
- `notify` and `readmodel` are empty today. There is no WebSocket hub or cross-instance
  fan-out implementation; the architecture's notification routes describe target
  state.
- The existing outbox cannot simply be called “broadcast”. Its relays are competing
  consumers: two instances claim around one another, and a logical consumer receives
  a fact once across the pair. Delivery rows are created for statically registered
  consumers. Registering ephemeral instance ids would make dead instances hold
  retention open and would still need membership and backfill rules.
- 1.x keeps connections in process-local maps. A restart loses them and a publish on
  one instance cannot reach another. Its durable notification write and live publish
  are separate operations, so either can succeed alone.
- Sticky routing is not a complete answer. It chooses the instance that holds the
  browser; it does not choose the instance that processes the event. The producer
  still needs to reach the socket-holding instance.

## Options and costs

| Option | Shape | Principal cost and commitment |
|---|---|---|
| **A. Database broadcast feed** | Commit a small monotonic signal; every instance tails every new signal and pushes to its local sockets | A polling load/latency floor and a short-lived growing table; no new infrastructure |
| **B. Elected WebSocket hub** | One leader owns all sockets and consumes committed signals | Leader election, reverse-proxy failover and a new availability bottleneck |
| **C. Peer-to-peer HTTP broadcast** | The producer discovers and calls every runtime peer | Membership, authentication, partial-failure handling, retries and version compatibility |
| **D. Browser polling only** | Remove server push; browsers periodically re-query | Simplest operations, but withdraws the architecture's live-channel promise and increases query traffic |

### A — database broadcast feed

The authoritative mutation and a tiny `notify`-owned signal commit in one transaction.
Every runtime instance independently reads signals after its local cursor; there is
**no claim, no per-instance acknowledgement and no promise that a socket write is
retried**. Each instance pushes only to sockets it holds.

The signal contains no business payload: site, topic/entity identity, sequence and
creation time are enough to say “your view may have changed”. The browser re-queries
the authorised API for data. Duplicate signals are harmless; a sequence gap or
reconnect triggers the same re-query.

Cost: a constant indexed poll on every instance, normal latency bounded by the poll
interval, lag/health metrics and short retention. A stopped instance never holds
retention open. If the database is unavailable, both authoritative reads and the
signal feed stop together rather than allowing an in-memory view to diverge.

### B — elected WebSocket hub

One static or elected instance owns all sockets. Other instances commit signals for
it to consume. This can avoid N identical polls, but it adds a singleton to a service
designed for more than one instance. The reverse proxy must find the current hub and
reconnect every browser on failover; that proxy is not built yet. A hub failure is
recoverable through reconnect/re-query, but disconnects the whole console population
at once.

### C — peer-to-peer broadcast

The producing instance sends the hint to every sibling. It has the lowest direct
latency, but no peer registry exists. ORCA would need membership, authenticated
internal routes, timeouts, backpressure, rolling-version compatibility and
observability for partial success. Without persistence an unreachable peer can leave
still-connected browsers stale; with durable retry this becomes Option A with more
moving parts.

### D — browser polling only

This is a valid simplification if Product is willing to withdraw WebSocket push.
Browsers poll authoritative grids and notifications with backoff. It has no
cross-instance problem, but trades it for a chosen staleness window and continuing
query load per open browser. It should remain the fallback even if another option is
chosen.

Reopening ADR-007 to install a broker is deliberately not presented as a normal
option. If the product needs guaranteed live delivery or sustained sub-poll-interval
latency at scale, that is a larger architecture decision, not a hidden implementation
choice inside stream 2.

## Recommendation

**Recommend Option A: a database-backed broadcast feed, explicitly disposable and
best effort, with reconnect-and-re-query as the correctness mechanism.** This is
advice; the product owner rules.

Treat the following as part of that ruling:

1. **Commit signal and truth together.** A durable notification or projection change
   cannot commit without its invalidation signal, or vice versa.
2. **Hints carry no protected payload.** They identify what may have changed. The
   scoped API remains the only data path to the browser.
3. **Every instance tails; none claims.** Do not register runtime instance ids as
   outbox consumers and do not make dead processes retention participants.
4. **The client repairs state.** Query on first connect, reconnect, socket replacement
   and detected sequence gap. Duplicate hints are accepted.
5. **Latency is an explicit product expectation.** The poll interval is installation
   tunable and measured. Product chooses the customer-visible latency envelope; the
   implementer proposes a default from measurement rather than inventing one here.
6. **The feed is operated.** Index by scope/sequence, expose lag and last-success
   metrics, bound row retention, and keep the envelope compatible across a rolling
   upgrade.
7. **Socket failure is not durable failure.** A failed write is not retried forever;
   reconnect and query recover. Durable notifications remain in their own table.

## What each ruling commits the programme to

| Ruling | Product commitment |
|---|---|
| **A** | A small polling loop and growing signal table; latency equals the chosen interval; no new server |
| **B** | A hub role, leader/failover machinery and reverse-proxy work; all sockets share that failure domain |
| **C** | Peer discovery and a new authenticated internal surface with partial-failure semantics |
| **D** | No live push promise; browser polling load and bounded visible staleness |

## Security and scope consequences kept separate

- A short-lived, single-use WebSocket ticket must redeem atomically across instances.
  An in-memory redeemed set is replayable on another node and disappears on restart.
  Shared database redemption (or a signed ticket plus shared consumed nonce) is a
  separate security design; this fan-out ruling must not silently choose it.
- Sticky routing may reduce reconnect churn, but it complements rather than replaces
  fan-out.
- Whether `notify` also delivers email or push is stream 2 Q5. It does not change how
  an in-app WebSocket hint crosses runtime instances.
- Option A adds a retention-class input. Its class and duration belong in the
  retention ruling, not in this document.

## Ruling requested

1. Choose **A, B, C or D**.
2. If A, confirm the seven conditions above, especially hint-only payloads and
   query-on-reconnect.
3. State the acceptable live-update latency envelope, or authorise the implementer
   to measure and return with a proposed default for ratification.

Until that ruling, stream 2 should build through the ticket and durable-notification
halves, then stop before cross-instance fan-out exactly as its plan instructs.
