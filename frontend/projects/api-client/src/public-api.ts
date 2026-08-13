/*
 * Typed clients GENERATED from the services' own OpenAPI documents.
 *
 * Do not edit anything under lib/runtime or lib/core by hand: `npm run api:generate`
 * overwrites both, and CI fails when the checked-in output differs from what the
 * contracts produce (`npm run api:check`). A contract change is meant to break this
 * build — that is what keeps the frontend honest, exactly as ContractInterfaceRule
 * does on the backend.
 *
 * Only the PUBLIC surface is generated. `/internal/**` endpoints carry the
 * per-installation shared credential (ADR-011) and must never reach a browser, so
 * their tags are excluded in openapitools.json.
 *
 * Both services are namespaced, because the same type name (ApiResponse, ApiError)
 * exists in both contracts.
 */
export * as runtime from './lib/runtime';
export * as core from './lib/core';
