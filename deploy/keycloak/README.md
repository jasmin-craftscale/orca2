# Keycloak realm — what this is, and what it is not

`realm-export.json` is imported on startup by `docker compose up`. It gives a
developer a working identity server with no clicking: one realm, one client per
service, a service account on each.

| | |
|---|---|
| Realm | `orca` |
| Issuer | `http://localhost:8080/realms/orca` |
| JWKS | `http://localhost:8080/realms/orca/protocol/openid-connect/certs` |
| Clients | `orca-core` · `orca-runtime` · `orca-edge` · `orca-portal` · `orca-sync` · `orca-fleet` |
| Grant | `client_credentials` only. A service is not a browser and never sees a login page |
| Realm role | `orca-service`, held by every service account |
| Audience | `orca`, added by the `orca-audience` client scope |

Get a token:

```
curl -s -d grant_type=client_credentials -d client_id=orca-core \
     -d client_secret=local-dev-secret-orca-core \
     http://localhost:8080/realms/orca/protocol/openid-connect/token
```

Every service validates that token **by signature, locally**, against the realm's
published keys. Nothing calls Keycloak per request (§B6) — which is also what
lets a site keep working when the wide-area link drops.

## ⚠️ Since 7 Aug 2026: Keycloak authenticates people, not services

ADR-011 was narrowed: **service-to-service calls no longer carry a Keycloak
token.** They present the per-installation shared credential
(`ORCA_INTERNAL_CREDENTIAL`) on `/internal/**`, validated locally with no
identity provider on the request path — so nothing on the gate path ever needs to
*mint* a token, which is the half of item #7 that no caching strategy could fix.

The six service-account clients below therefore carry **no weight in the target
design**. They remain in this fixture because they are the easiest way for a
developer to mint a user-shaped token and exercise a resource server locally.

## ⚠️ What this file does NOT decide

**This is a local development fixture. It is not the realm design.**

Two items in `ORCA_OPEN_QUESTIONS_REGISTER.md` are open and are deliberately
**not** answered here:

- **U3 — the realm and client structure is free to design.** The register's advice
  is explicit that it should be settled *together with* item #7, in one design
  session, because deciding them separately is how the current two-realm structure
  accumulated rather than got designed.
- **#7 — Keycloak local per site, or central with break-glass.** Token
  *validation* is local signature verification either way; token *issuing* is not,
  and under a central deployment a lane is reported to stop accepting logins five
  to ten minutes into a WAN outage. No caching strategy fixes issuance.

The shape here — one realm, one client per service, service accounts — is what the
Phase 0 brief specifies for the local stack, and nothing more should be read into
it. In particular it takes **no position** on how many realms a deployment has,
where Keycloak runs, how the portal's cross-customer principals are represented,
or how identity-provider claims map to platform entitlements.

Anyone reaching for this file as a starting point for the production realm should
close U3 and #7 first.
